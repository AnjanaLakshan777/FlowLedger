package com.flowledger.cashmanagement.service;

import com.flowledger.cashmanagement.dto.ApproveSalesImportRequest;
import com.flowledger.cashmanagement.dto.RejectSalesImportRequest;
import com.flowledger.cashmanagement.dto.SalesImportBatchResponse;
import com.flowledger.cashmanagement.dto.SalesImportDetailResponse;
import com.flowledger.cashmanagement.dto.SalesImportOverviewResponse;
import com.flowledger.cashmanagement.exception.InvalidImportRequestException;
import com.flowledger.cashmanagement.exception.SalesImportConflictException;
import com.flowledger.cashmanagement.exception.SalesImportNotFoundException;
import com.flowledger.cashmanagement.repository.AuditEventStore;
import com.flowledger.cashmanagement.salesimport.SalesImportCsvParser;
import com.flowledger.cashmanagement.salesimport.SalesImportCsvParser.ParsedFile;
import com.flowledger.cashmanagement.salesimport.SalesImportCsvParser.ParsedRow;
import com.flowledger.cashmanagement.salesimport.SalesImportCsvParser.RowError;
import com.flowledger.cashmanagement.salesimport.SalesImportStore;
import com.flowledger.cashmanagement.salesimport.SalesImportUpload;
import com.flowledger.cashmanagement.security.CompanyAccessVerifier;
import com.flowledger.cashmanagement.security.CompanyRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Historical sales import: upload and validation, review, and the Finance Controller's approval or
 * rejection. Company access is checked with identity-service before any database transaction.
 * Approving records the valid rows as daily sales (entry source HISTORICAL_IMPORT) in one
 * transaction with the status change and the audit event; nothing here creates or implies money
 * in the bank, and nothing is sent to forecast-service (see docs/decisions.md).
 * <p>
 * Logs carry batch IDs, counts and statuses only, never row contents.
 */
@Slf4j
@Service
public class SalesImportService {

    // Confirmed: Accountants upload and inspect; Finance Controllers also approve and reject;
    // Company Admins inspect, approve and reject but do not upload; Cashiers have no access.
    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.ACCOUNTANT, CompanyRole.FINANCE_CONTROLLER, CompanyRole.COMPANY_ADMIN);
    private static final Set<CompanyRole> UPLOAD_ROLES =
            EnumSet.of(CompanyRole.ACCOUNTANT, CompanyRole.FINANCE_CONTROLLER);
    private static final Set<CompanyRole> DECIDE_ROLES =
            EnumSet.of(CompanyRole.FINANCE_CONTROLLER, CompanyRole.COMPANY_ADMIN);

    private static final String ENTITY_TYPE = "SALES_IMPORT_BATCH";
    private static final int MAX_LISTED_DATES = 50;

    private final SalesImportStore store;
    private final AuditEventStore audit;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public SalesImportService(SalesImportStore store, AuditEventStore audit,
                              CompanyAccessVerifier companyAccessVerifier, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.store = store;
        this.audit = audit;
        this.companyAccessVerifier = companyAccessVerifier;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public SalesImportOverviewResponse list(Jwt user, UUID companyId) {
        CompanyRole role = companyAccessVerifier.authorize(user, companyId, VIEW_ROLES);
        boolean ownOnly = role == CompanyRole.ACCOUNTANT;
        List<SalesImportBatchResponse> imports = readTransaction.execute(status ->
                store.listBatches(companyId, ownOnly ? userId(user) : null));
        return new SalesImportOverviewResponse(UPLOAD_ROLES.contains(role), DECIDE_ROLES.contains(role), ownOnly,
                imports);
    }

    public SalesImportDetailResponse get(Jwt user, UUID companyId, UUID importId) {
        CompanyRole role = companyAccessVerifier.authorize(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> detail(companyId, importId, role, userId(user)));
    }

    public SalesImportDetailResponse upload(Jwt user, UUID companyId, MultipartFile file) {
        companyAccessVerifier.authorize(user, companyId, UPLOAD_ROLES);
        SalesImportUpload upload = SalesImportUpload.from(file);
        UUID uploader = userId(user);
        // Parsing needs no database; only the checks against recorded sales do.
        ParsedFile parsed = SalesImportCsvParser.parse(upload.content(), LocalDate.now(clock));

        UUID batchId = writeTransaction.execute(status -> {
            store.lockCompanyImports(companyId);
            store.activeBatchWithFile(companyId, upload.sha256()).ifPresent(existing -> {
                throw SalesImportConflictException.duplicateFile(existing);
            });
            List<SalesImportStore.NewRow> rows = classify(companyId, parsed);
            SalesImportStore.NewBatch batch = batch(companyId, upload, uploader, parsed.fileError(), rows);
            UUID id;
            try {
                id = store.insertBatch(batch);
            } catch (DuplicateKeyException e) {
                // The same file was uploaded concurrently; the unique index kept one.
                throw SalesImportConflictException.duplicateFile(null);
            }
            store.insertRows(id, rows);
            audit.record(companyId, uploader, "SALES_IMPORT_UPLOADED", ENTITY_TYPE, id, null, null,
                    summary(batch));
            log.info("Sales import {} uploaded: {} ({} rows, {} valid, {} invalid, {} duplicate)", id, batch.status(),
                    batch.rowCount(), batch.validRowCount(), batch.invalidRowCount(), batch.duplicateRowCount());
            return id;
        });
        return readTransaction.execute(status -> detail(companyId, batchId, null, uploader));
    }

    public SalesImportDetailResponse approve(Jwt user, UUID companyId, UUID importId,
                                             ApproveSalesImportRequest request) {
        companyAccessVerifier.authorize(user, companyId, DECIDE_ROLES);
        UUID approver = userId(user);
        String note = blankToNull(request.note());

        writeTransaction.executeWithoutResult(status -> {
            store.lockCompanyImports(companyId);
            SalesImportBatchResponse batch = pendingBatch(companyId, importId, request.version());
            if (batch.duplicateRowCount() > 0 || batch.validRowCount() == 0) {
                throw SalesImportConflictException.notPending();
            }
            if (batch.invalidRowCount() > 0 && !request.leavesOutInvalidRows()) {
                throw new InvalidImportRequestException("excludeInvalidRows", "This import has "
                        + batch.invalidRowCount() + " invalid rows. Confirm that they are left out, or reject it.");
            }
            // Sales recorded since the upload (entered by hand or another approved import) win;
            // the whole import is refused rather than partly recorded.
            List<LocalDate> conflicts = store.recordedDates(companyId, store.validDates(importId));
            if (!conflicts.isEmpty()) {
                throw SalesImportConflictException.datesAlreadyRecorded(
                        conflicts.stream().limit(MAX_LISTED_DATES).toList());
            }
            int created;
            try {
                created = store.recordApprovedSales(importId);
            } catch (DataIntegrityViolationException e) {
                // A sale entered by hand for one of the dates committed after the check above.
                throw SalesImportConflictException.datesAlreadyRecorded(List.of());
            }
            if (!store.markApproved(importId, request.version(), approver, note, batch.validRowCount(),
                    batch.invalidRowCount())) {
                throw SalesImportConflictException.stale();
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "APPROVED");
            after.put("importedRowCount", batch.validRowCount());
            after.put("excludedRowCount", batch.invalidRowCount());
            after.put("dailySalesCreated", created);
            audit.record(companyId, approver, "SALES_IMPORT_APPROVED", ENTITY_TYPE, importId, note,
                    Map.of("status", batch.status(), "version", batch.version()), after);
            log.info("Sales import {} approved: {} rows imported, {} daily sales created, {} rows left out",
                    importId, batch.validRowCount(), created, batch.invalidRowCount());
        });
        return readTransaction.execute(status -> detail(companyId, importId, null, approver));
    }

    public SalesImportDetailResponse reject(Jwt user, UUID companyId, UUID importId,
                                            RejectSalesImportRequest request) {
        companyAccessVerifier.authorize(user, companyId, DECIDE_ROLES);
        UUID rejecter = userId(user);
        String reason = request.reason().strip();

        writeTransaction.executeWithoutResult(status -> {
            SalesImportBatchResponse batch = pendingBatch(companyId, importId, request.version());
            if (!store.markRejected(importId, request.version(), rejecter, reason)) {
                throw SalesImportConflictException.stale();
            }
            audit.record(companyId, rejecter, "SALES_IMPORT_REJECTED", ENTITY_TYPE, importId, reason,
                    Map.of("status", batch.status(), "version", batch.version()), Map.of("status", "REJECTED"));
            log.info("Sales import {} rejected", importId);
        });
        return readTransaction.execute(status -> detail(companyId, importId, null, rejecter));
    }

    // The batch locked for a decision: it must exist in the company, wait for approval, and still
    // be the version the user reviewed.
    private SalesImportBatchResponse pendingBatch(UUID companyId, UUID importId, int version) {
        SalesImportBatchResponse batch = store.lockBatch(companyId, importId)
                .orElseThrow(SalesImportNotFoundException::new);
        if (!"PENDING_APPROVAL".equals(batch.status())) {
            throw SalesImportConflictException.notPending();
        }
        if (batch.version() != version) {
            throw SalesImportConflictException.stale();
        }
        return batch;
    }

    // An Accountant (role ACCOUNTANT) sees only imports they uploaded; other imports are "not found".
    private SalesImportDetailResponse detail(UUID companyId, UUID importId, CompanyRole role, UUID userId) {
        SalesImportBatchResponse batch = store.findBatch(companyId, importId)
                .filter(found -> role != CompanyRole.ACCOUNTANT || found.uploadedBy().equals(userId))
                .orElseThrow(SalesImportNotFoundException::new);
        return new SalesImportDetailResponse(batch, store.rows(importId));
    }

    // Row statuses: DUPLICATE for a date repeated in the file or already recorded, INVALID for any
    // other problem, VALID otherwise. Cells are kept as written only for rows that are not valid.
    private List<SalesImportStore.NewRow> classify(UUID companyId, ParsedFile parsed) {
        if (parsed.fileError() != null) {
            return List.of();
        }
        Map<LocalDate, List<Integer>> rowsByDate = new TreeMap<>();
        for (ParsedRow row : parsed.rows()) {
            if (row.saleDate() != null) {
                rowsByDate.computeIfAbsent(row.saleDate(), date -> new ArrayList<>()).add(row.rowNumber());
            }
        }
        Set<LocalDate> recorded = new HashSet<>(store.recordedDates(companyId, rowsByDate.keySet()));

        List<SalesImportStore.NewRow> rows = new ArrayList<>();
        for (ParsedRow row : parsed.rows()) {
            List<RowError> errors = new ArrayList<>(row.errors());
            boolean repeated = row.saleDate() != null && rowsByDate.get(row.saleDate()).size() > 1;
            boolean alreadyRecorded = row.saleDate() != null && recorded.contains(row.saleDate());
            if (repeated) {
                String others = rowsByDate.get(row.saleDate()).stream()
                        .filter(number -> number != row.rowNumber())
                        .limit(10)
                        .map(String::valueOf)
                        .collect(Collectors.joining(", "));
                errors.add(new RowError("date", "DUPLICATE_IN_FILE",
                        "This date is also on row " + others + ". Keep one row per date."));
            }
            if (alreadyRecorded) {
                errors.add(new RowError("date", "DATE_ALREADY_RECORDED",
                        "Sales are already recorded for this date."));
            }
            String status = repeated || alreadyRecorded ? "DUPLICATE" : errors.isEmpty() ? "VALID" : "INVALID";
            rows.add(new SalesImportStore.NewRow(row.rowNumber(), row.saleDate(), row.cashSales(), row.cardSales(),
                    status, repeated, errors, "VALID".equals(status) ? null : row.raw()));
        }
        return rows;
    }

    private static SalesImportStore.NewBatch batch(UUID companyId, SalesImportUpload upload, UUID uploader,
                                                   String fileError, List<SalesImportStore.NewRow> rows) {
        List<SalesImportStore.NewRow> valid = rows.stream().filter(row -> "VALID".equals(row.status())).toList();
        int invalid = (int) rows.stream().filter(row -> "INVALID".equals(row.status())).count();
        int duplicate = (int) rows.stream().filter(row -> "DUPLICATE".equals(row.status())).count();
        String status = fileError != null || duplicate > 0 || valid.isEmpty() ? "INVALID" : "PENDING_APPROVAL";
        return new SalesImportStore.NewBatch(companyId, upload.filename().isEmpty() ? "upload.csv" : upload.filename(),
                upload.sha256(), upload.content().length, status, fileError,
                valid.stream().map(SalesImportStore.NewRow::saleDate).min(LocalDate::compareTo).orElse(null),
                valid.stream().map(SalesImportStore.NewRow::saleDate).max(LocalDate::compareTo).orElse(null),
                rows.size(), valid.size(), invalid, duplicate,
                valid.stream().map(SalesImportStore.NewRow::cashSales).reduce(BigDecimal.ZERO, BigDecimal::add),
                valid.stream().map(SalesImportStore.NewRow::cardSales).reduce(BigDecimal.ZERO, BigDecimal::add),
                uploader);
    }

    private static Map<String, Object> summary(SalesImportStore.NewBatch batch) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", batch.status());
        summary.put("fileName", batch.filename());
        summary.put("fileSha256", batch.sha256());
        summary.put("rowCount", batch.rowCount());
        summary.put("validRowCount", batch.validRowCount());
        summary.put("invalidRowCount", batch.invalidRowCount());
        summary.put("duplicateRowCount", batch.duplicateRowCount());
        summary.put("periodStart", batch.periodStart() == null ? null : batch.periodStart().toString());
        summary.put("periodEnd", batch.periodEnd() == null ? null : batch.periodEnd().toString());
        return summary;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static UUID userId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
