package com.flowledger.bankintegration.service;

import com.flowledger.bankintegration.dto.StatementImportResponse;
import com.flowledger.bankintegration.entity.BankAccount;
import com.flowledger.bankintegration.entity.BankAccountStatus;
import com.flowledger.bankintegration.entity.BankAccountType;
import com.flowledger.bankintegration.entity.BankIntegrationAuditEvent;
import com.flowledger.bankintegration.entity.BankStatementImportBatch;
import com.flowledger.bankintegration.entity.ImportBatchStatus;
import com.flowledger.bankintegration.entity.IntegrationMode;
import com.flowledger.bankintegration.entity.OutboxEvent;
import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.exception.BankAccountNotActiveException;
import com.flowledger.bankintegration.exception.BankAccountNotFoundException;
import com.flowledger.bankintegration.exception.ConnectionModeMismatchException;
import com.flowledger.bankintegration.exception.InvalidRequestException;
import com.flowledger.bankintegration.exception.MonthAlreadyImportedException;
import com.flowledger.bankintegration.exception.StatementImportAlreadyRemovedException;
import com.flowledger.bankintegration.exception.StatementImportNotFoundException;
import com.flowledger.bankintegration.exception.StatementRejectedException;
import com.flowledger.bankintegration.repository.BankAccountRepository;
import com.flowledger.bankintegration.repository.BankIntegrationAuditEventRepository;
import com.flowledger.bankintegration.repository.BankStatementImportBatchRepository;
import com.flowledger.bankintegration.repository.OutboxEventRepository;
import com.flowledger.bankintegration.repository.StatementImportStore;
import com.flowledger.bankintegration.security.CompanyAccessVerifier;
import com.flowledger.bankintegration.security.CompanyRole;
import com.flowledger.bankintegration.statement.MonthlyTemplateCsvParser;
import com.flowledger.bankintegration.statement.ParsedStatement;
import com.flowledger.bankintegration.statement.StatementError;
import com.flowledger.bankintegration.statement.StatementLine;
import com.flowledger.bankintegration.statement.StatementUpload;
import com.flowledger.bankintegration.statement.TransactionDetailsCsvParser;
import com.flowledger.bankintegration.statement.TransactionFingerprints;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Imports bank statements. The membership check with identity-service finishes before any
 * database transaction starts. A statement is imported completely or not at all: an invalid file
 * is recorded as a FAILED batch with its errors and no transactions.
 */
@Slf4j
@Service
public class StatementImportService {

    // Importing is bank data entry, like "bank.retrySync" in the UI matrix. Statements are company
    // business data, so a SYSTEM_ADMIN needs one of these company roles too.
    private static final Set<CompanyRole> IMPORT_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER);
    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);
    // The Accountant enters each month's bank transactions from the template, and so may the roles above;
    // whoever may upload a month may also remove an import made by mistake.
    private static final Set<CompanyRole> MONTHLY_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);

    private static final String BATCH_ENTITY = "BANK_STATEMENT_IMPORT_BATCH";

    private final BankStatementImportBatchRepository batchRepository;
    private final BankAccountRepository bankAccountRepository;
    private final StatementImportStore importStore;
    private final BankIntegrationAuditEventRepository auditEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionDetailsCsvParser parser;
    private final MonthlyTemplateCsvParser monthlyParser;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public StatementImportService(BankStatementImportBatchRepository batchRepository,
                                  BankAccountRepository bankAccountRepository,
                                  StatementImportStore importStore,
                                  BankIntegrationAuditEventRepository auditEventRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  TransactionDetailsCsvParser parser,
                                  MonthlyTemplateCsvParser monthlyParser,
                                  CompanyAccessVerifier companyAccessVerifier,
                                  Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.batchRepository = batchRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.importStore = importStore;
        this.auditEventRepository = auditEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.parser = parser;
        this.monthlyParser = monthlyParser;
        this.companyAccessVerifier = companyAccessVerifier;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public StatementImportResponse importStatement(Jwt user, UUID companyId, UUID bankAccountId, MultipartFile file) {
        companyAccessVerifier.verify(user, companyId, IMPORT_ROLES);
        StatementUpload upload = StatementUpload.from(file);
        ParsedStatement statement = parser.parse(upload.content());
        String fileSha256 = TransactionFingerprints.sha256(upload.content());
        UUID actor = UUID.fromString(user.getSubject());

        BankStatementImportBatch batch = writeTransaction.execute(status -> {
            BankAccount account = activeCurrentAccount(companyId, bankAccountId);
            if (statement.hasErrors()) {
                return recordRejection(account, upload, statement, fileSha256, actor);
            }
            importStore.lockAccountImports(account.getId());
            // A month entered from the template already holds these days, with other descriptions, so
            // the bank's rows would not be recognised as duplicates.
            List<String> months = importStore.importedOverlapping(companyId, account.getId(), statement.periodStart(),
                    statement.periodEnd(), MonthlyTemplateCsvParser.FORMAT);
            if (!months.isEmpty()) {
                throw new MonthAlreadyImportedException("These dates are already imported from the monthly template ("
                        + String.join(", ", months) + "). Remove that import first.");
            }
            return importRows(account, upload, statement, fileSha256, actor, statement.periodStart(),
                    statement.periodEnd());
        });
        return finish(batch, statement);
    }

    /**
     * Imports one month of the current account's transactions from FlowLedger's monthly template,
     * each with its category. Only a month that has ended can be imported, and each month only once
     * (remove the earlier import to replace it). The import covers every day of the month, so a day
     * without transactions counts as a known day with none. When the previous month was imported,
     * this month must open with its closing balance, so no rows are missing between them.
     */
    public StatementImportResponse importMonth(Jwt user, UUID companyId, UUID bankAccountId, String monthText,
                                               MultipartFile file) {
        companyAccessVerifier.verify(user, companyId, MONTHLY_ROLES);
        YearMonth month = parseMonth(monthText);
        if (!month.isBefore(YearMonth.now(clock))) {
            throw new InvalidRequestException("Bank transactions are imported after the month ends. "
                    + MonthlyTemplateCsvParser.monthName(month) + " has not ended yet.");
        }
        StatementUpload upload = StatementUpload.from(file);
        ParsedStatement parsed = monthlyParser.parse(upload.content(), month);
        String fileSha256 = TransactionFingerprints.sha256(upload.content());
        UUID actor = UUID.fromString(user.getSubject());
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        ParsedStatement[] checked = {parsed};
        BankStatementImportBatch batch = writeTransaction.execute(status -> {
            BankAccount account = activeCurrentAccount(companyId, bankAccountId);
            importStore.lockAccountImports(account.getId());
            List<String> overlapping = importStore.importedOverlapping(companyId, account.getId(), from, to, null);
            if (!overlapping.isEmpty()) {
                throw new MonthAlreadyImportedException("Bank transactions for " + MonthlyTemplateCsvParser.monthName(month)
                        + " are already imported (" + String.join(", ", overlapping) + "). Remove that import first to "
                        + "replace it.");
            }
            if (!parsed.hasErrors()) {
                checked[0] = checkOpeningBalance(account, parsed, from);
            }
            return checked[0].hasErrors()
                    ? recordRejection(account, upload, checked[0], fileSha256, actor)
                    : importRows(account, upload, checked[0], fileSha256, actor, from, to);
        });
        return finish(batch, checked[0]);
    }

    // The month opens with the balance before its oldest row; it must equal last month's imported closing balance.
    private ParsedStatement checkOpeningBalance(BankAccount account, ParsedStatement statement, LocalDate monthStart) {
        StatementLine oldest = statement.transactionsOldestFirst().isEmpty()
                ? statement.latestLine() : statement.transactionsOldestFirst().getFirst();
        BigDecimal opening = oldest.runningBalance().subtract(oldest.signedAmount());
        Optional<BigDecimal> previous = importStore.importedClosingBalance(account.getCompanyId(), account.getId(),
                monthStart.minusDays(1));
        if (previous.isEmpty() || previous.get().compareTo(opening) == 0) {
            return statement;
        }
        return ParsedStatement.failed(statement.format(), List.of(new StatementError(oldest.lineNumber(),
                "Running Balance", "The month opens with LKR " + opening.toPlainString() + " (the oldest Running Balance "
                + "less its amount), but last month's import closed with LKR " + previous.get().toPlainString()
                + ". Check for missing or extra rows.")));
    }

    private static YearMonth parseMonth(String text) {
        try {
            return YearMonth.parse(text == null ? "" : text.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("month must be a month in the format YYYY-MM, for example 2026-09.");
        }
    }

    private StatementImportResponse finish(BankStatementImportBatch batch, ParsedStatement statement) {
        if (statement.hasErrors()) {
            log.info("Statement import {} rejected with {} errors", batch.getId(), statement.errors().size());
            throw new StatementRejectedException(batch.getId(), statement.errors());
        }
        log.info("Statement import {}: {} imported, {} duplicates, {} skipped", batch.getId(),
                batch.getImportedRowCount(), batch.getDuplicateRowCount(), batch.getSkippedRowCount());
        return readTransaction.execute(status -> response(batch));
    }

    /**
     * Removes an import, for a statement uploaded by mistake. The transactions it added and the
     * balance it recorded are deleted, so the latest balance falls back to the one before; the batch
     * stays as REMOVED history, and the audit history keeps what was removed. Rows the import found
     * already imported belong to an earlier import and are kept.
     */
    public StatementImportResponse removeImport(Jwt user, UUID companyId, UUID batchId) {
        companyAccessVerifier.verify(user, companyId, MONTHLY_ROLES);
        UUID actor = UUID.fromString(user.getSubject());

        BankStatementImportBatch batch = writeTransaction.execute(status -> {
            BankStatementImportBatch found = batchRepository.lockByCompanyIdAndId(companyId, batchId)
                    .orElseThrow(StatementImportNotFoundException::new);
            if (found.getStatus() == ImportBatchStatus.REMOVED) {
                throw new StatementImportAlreadyRemovedException();
            }
            int transactions = importStore.deleteBatchTransactions(companyId, batchId);
            int balances = importStore.deleteBatchBalanceSnapshots(companyId, batchId);
            found.markRemoved(actor, clock.instant());
            found = batchRepository.saveAndFlush(found);

            Map<String, Object> after = batchDetails(found);
            after.put("deletedTransactionCount", transactions);
            after.put("deletedBalanceCount", balances);
            audit(found, "BANK_STATEMENT_REMOVED", actor, after);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("companyId", companyId.toString());
            payload.put("bankAccountId", found.getBankAccountId().toString());
            payload.put("batchId", batchId.toString());
            payload.put("deletedTransactionCount", transactions);
            payload.put("deletedBalanceCount", balances);
            publish("BankStatementImportBatch", batchId, "BankStatementImportRemoved", payload);
            log.info("Statement import {} removed: {} transactions and {} balances deleted", batchId, transactions, balances);
            return found;
        });
        return readTransaction.execute(status -> response(batch));
    }

    public StatementImportResponse getImport(Jwt user, UUID companyId, UUID batchId) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> batchRepository.findByCompanyIdAndId(companyId, batchId)
                .map(this::response)
                .orElseThrow(StatementImportNotFoundException::new));
    }

    public List<StatementImportResponse> listImports(Jwt user, UUID companyId) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> batchRepository.findByCompanyIdOrderByImportedAtDesc(companyId).stream()
                .map(this::response)
                .toList());
    }

    private BankAccount activeCurrentAccount(UUID companyId, UUID bankAccountId) {
        BankAccount account = bankAccountRepository.findByCompanyIdAndId(companyId, bankAccountId)
                .orElseThrow(BankAccountNotFoundException::new);
        if (account.getStatus() != BankAccountStatus.ACTIVE || account.getAccountType() != BankAccountType.CURRENT) {
            throw new BankAccountNotActiveException();
        }
        if (account.getIntegrationMode() != IntegrationMode.STATEMENT_IMPORT) {
            throw new ConnectionModeMismatchException(
                    "This account's bank data is entered manually. Change its connection to bank statement import first.");
        }
        return account;
    }

    private BankStatementImportBatch recordRejection(BankAccount account, StatementUpload upload,
                                                     ParsedStatement statement, String fileSha256, UUID actor) {
        BankStatementImportBatch batch = batchRepository.save(BankStatementImportBatch.failed(
                account.getCompanyId(), account.getId(), upload.filename(), fileSha256, statement.format(), actor,
                statement.errors()));
        Map<String, Object> after = batchDetails(batch);
        after.put("errorCount", statement.errors().size());
        audit(batch, "BANK_STATEMENT_REJECTED", actor, after);
        return batch;
    }

    /**
     * Stores the rows and the closing balance. The period is the days the import covers: the
     * statement's first to last transaction date, or the whole month for a monthly import. The
     * balance after the newest row applies at the end of the period's last day.
     */
    private BankStatementImportBatch importRows(BankAccount account, StatementUpload upload, ParsedStatement statement,
                                                String fileSha256, UUID actor, LocalDate periodStart,
                                                LocalDate periodEnd) {
        BankStatementImportBatch batch = batchRepository.saveAndFlush(BankStatementImportBatch.imported(
                account.getCompanyId(), account.getId(), upload.filename(), fileSha256, statement.format(), actor,
                periodStart, periodEnd, statement.lines().size()));

        List<StatementLine> transactions = statement.transactionsOldestFirst();
        List<String> fingerprints = TransactionFingerprints.of(transactions);
        Instant now = clock.instant();
        int imported = 0;
        for (int i = 0; i < transactions.size(); i++) {
            StatementLine line = transactions.get(i);
            Optional<UUID> transactionId = importStore.insertTransaction(account.getCompanyId(), account.getId(),
                    account.getCurrency(), batch.getId(), line, fingerprints.get(i), actor, now);
            if (transactionId.isPresent()) {
                imported++;
                publish("BankTransaction", transactionId.get(), "BankTransactionImported",
                        transactionPayload(account, transactionId.get(), line));
            }
        }

        // The statement has no times, so its closing balance applies at the end of the period.
        StatementLine latest = statement.latestLine();
        OffsetDateTime balanceAsOf = periodEnd.atTime(LocalTime.of(23, 59, 59))
                .atZone(clock.getZone()).toOffsetDateTime();
        boolean snapshotRecorded = importStore.insertBalanceSnapshot(account.getCompanyId(), account.getId(),
                account.getCurrency(), latest.runningBalance(), balanceAsOf, batch.getId());
        if (snapshotRecorded) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("companyId", account.getCompanyId().toString());
            payload.put("bankAccountId", account.getId().toString());
            payload.put("balanceAmount", latest.runningBalance().toPlainString());
            payload.put("currency", account.getCurrency());
            payload.put("balanceAsOf", balanceAsOf.toString());
            publish("BankAccount", account.getId(), "BankBalanceSnapshotRecorded", payload);
        }

        batch.recordOutcome(imported, transactions.size() - imported, Math.toIntExact(statement.zeroAmountCount()));

        Map<String, Object> after = batchDetails(batch);
        after.put("closingBalance", latest.runningBalance().toPlainString());
        after.put("balanceAsOf", balanceAsOf.toString());
        after.put("balanceSnapshotRecorded", snapshotRecorded);
        audit(batch, "BANK_STATEMENT_IMPORTED", actor, after);
        return batch;
    }

    private Map<String, Object> batchDetails(BankStatementImportBatch batch) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bankAccountId", batch.getBankAccountId().toString());
        details.put("filename", batch.getFilename());
        details.put("fileSha256", batch.getFileSha256());
        details.put("fileFormat", batch.getFileFormat());
        details.put("status", batch.getStatus().name());
        details.put("periodStart", batch.getPeriodStart() == null ? null : batch.getPeriodStart().toString());
        details.put("periodEnd", batch.getPeriodEnd() == null ? null : batch.getPeriodEnd().toString());
        details.put("rowCount", batch.getRowCount());
        details.put("importedRowCount", batch.getImportedRowCount());
        details.put("duplicateRowCount", batch.getDuplicateRowCount());
        details.put("skippedRowCount", batch.getSkippedRowCount());
        return details;
    }

    private void audit(BankStatementImportBatch batch, String action, UUID actor, Map<String, Object> after) {
        auditEventRepository.save(new BankIntegrationAuditEvent(
                batch.getCompanyId(), actor, action, BATCH_ENTITY, batch.getId(), null, null, after));
    }

    private static Map<String, Object> transactionPayload(BankAccount account, UUID transactionId, StatementLine line) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bankTransactionId", transactionId.toString());
        payload.put("companyId", account.getCompanyId().toString());
        payload.put("bankAccountId", account.getId().toString());
        payload.put("transactionDate", line.transactionDate().toString());
        payload.put("direction", line.direction().name());
        payload.put("amount", line.amount().toPlainString());
        payload.put("currency", account.getCurrency());
        payload.put("category", line.category().name());
        return payload;
    }

    private void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        outboxEventRepository.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload));
    }

    private StatementImportResponse response(BankStatementImportBatch batch) {
        return StatementImportResponse.from(batch, batchRepository.findEarlierImportsOfFile(
                batch.getCompanyId(), batch.getBankAccountId(), batch.getFileSha256(), batch.getId(),
                batch.getImportedAt()));
    }
}
