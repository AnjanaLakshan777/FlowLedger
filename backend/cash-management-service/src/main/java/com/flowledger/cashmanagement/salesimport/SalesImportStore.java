package com.flowledger.cashmanagement.salesimport;

import com.flowledger.cashmanagement.dto.SalesHistoryResponse;
import com.flowledger.cashmanagement.dto.SalesImportBatchResponse;
import com.flowledger.cashmanagement.dto.SalesImportRowResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * sales_import_batches, sales_import_rows, and the daily sales an approval records. Callers run
 * the writes of one upload or decision in one transaction.
 */
@Repository
@RequiredArgsConstructor
public class SalesImportStore {

    private static final String BATCH_COLUMNS = """
            id, original_filename, status, file_error, period_start, period_end, row_count, valid_row_count,
            invalid_row_count, duplicate_row_count, cash_sales_total, card_sales_total, uploaded_by, uploaded_at,
            approved_by, approved_at, approval_note, imported_row_count, excluded_row_count, rejected_by,
            rejected_at, rejection_reason, version
            """;

    private static final RowMapper<SalesImportBatchResponse> BATCH = (rs, rowNum) -> new SalesImportBatchResponse(
            rs.getObject("id", UUID.class),
            rs.getString("original_filename"),
            rs.getString("status"),
            rs.getString("file_error"),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            rs.getInt("row_count"),
            rs.getInt("valid_row_count"),
            rs.getInt("invalid_row_count"),
            rs.getInt("duplicate_row_count"),
            rs.getBigDecimal("cash_sales_total"),
            rs.getBigDecimal("card_sales_total"),
            rs.getObject("uploaded_by", UUID.class),
            instant(rs.getTimestamp("uploaded_at")),
            rs.getObject("approved_by", UUID.class),
            instant(rs.getTimestamp("approved_at")),
            rs.getString("approval_note"),
            (Integer) rs.getObject("imported_row_count"),
            (Integer) rs.getObject("excluded_row_count"),
            rs.getObject("rejected_by", UUID.class),
            instant(rs.getTimestamp("rejected_at")),
            rs.getString("rejection_reason"),
            rs.getInt("version"));

    private static final TypeReference<List<Map<String, String>>> ERRORS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> RAW = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public record NewBatch(UUID companyId, String filename, String sha256, int sizeBytes, String status,
                           String fileError, LocalDate periodStart, LocalDate periodEnd, int rowCount, int validRowCount,
                           int invalidRowCount, int duplicateRowCount, BigDecimal cashTotal, BigDecimal cardTotal,
                           UUID uploadedBy) {
    }

    public record NewRow(int rowNumber, LocalDate saleDate, BigDecimal cashSales, BigDecimal cardSales, String status,
                         boolean duplicateDate, List<SalesImportCsvParser.RowError> errors, Map<String, String> raw) {
    }

    /**
     * Serialises uploads and approvals of one company's imports until the transaction ends, so the
     * check for already recorded dates and the recording itself cannot interleave.
     */
    public void lockCompanyImports(UUID companyId) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "sales-import:" + companyId);
    }

    public Optional<UUID> activeBatchWithFile(UUID companyId, String sha256) {
        return jdbcTemplate.queryForList("""
                        SELECT id FROM sales_import_batches
                        WHERE company_id = ? AND file_sha256 = ? AND status IN ('PENDING_APPROVAL', 'APPROVED')
                        """, UUID.class, companyId, sha256).stream().findFirst();
    }

    /**
     * The given dates that already have sales: a daily sales record (entered or imported) or a
     * valid row of an approved import (which includes days imported with zero sales).
     */
    public List<LocalDate> recordedDates(UUID companyId, Collection<LocalDate> dates) {
        if (dates.isEmpty()) {
            return List.of();
        }
        String[] isoDates = dates.stream().map(LocalDate::toString).distinct().toArray(String[]::new);
        return jdbcTemplate.queryForList("""
                        SELECT sale_date FROM daily_sales
                        WHERE company_id = ? AND sale_date = ANY (?::date[])
                        UNION
                        SELECT r.sale_date FROM sales_import_rows r
                        JOIN sales_import_batches b ON b.id = r.batch_id
                        WHERE b.company_id = ? AND b.status = 'APPROVED' AND r.validation_status = 'VALID'
                          AND r.sale_date = ANY (?::date[])
                        ORDER BY 1
                        """, LocalDate.class, companyId, isoDates, companyId, isoDates);
    }

    public UUID insertBatch(NewBatch batch) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO sales_import_batches
                            (company_id, original_filename, file_sha256, file_size_bytes, status, file_error,
                             period_start, period_end, row_count, valid_row_count, invalid_row_count,
                             duplicate_row_count, cash_sales_total, card_sales_total, uploaded_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, batch.companyId(), batch.filename(), batch.sha256(), batch.sizeBytes(), batch.status(),
                batch.fileError(), batch.periodStart(), batch.periodEnd(), batch.rowCount(), batch.validRowCount(),
                batch.invalidRowCount(), batch.duplicateRowCount(), batch.cashTotal(), batch.cardTotal(),
                batch.uploadedBy());
    }

    public void insertRows(UUID batchId, List<NewRow> rows) {
        jdbcTemplate.batchUpdate("""
                        INSERT INTO sales_import_rows
                            (batch_id, row_number, sale_date, cash_sales_amount, card_sales_amount, validation_status,
                             duplicate_date, validation_errors, raw_row)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        """,
                rows, 500, (ps, row) -> {
                    ps.setObject(1, batchId);
                    ps.setInt(2, row.rowNumber());
                    ps.setObject(3, row.saleDate());
                    ps.setBigDecimal(4, row.cashSales());
                    ps.setBigDecimal(5, row.cardSales());
                    ps.setString(6, row.status());
                    ps.setBoolean(7, row.duplicateDate());
                    ps.setString(8, jsonMapper.writeValueAsString(row.errors()));
                    ps.setString(9, row.raw() == null ? null : jsonMapper.writeValueAsString(row.raw()));
                });
    }

    /** The company's imports, newest first; only those uploaded by uploadedBy when it is not null. */
    public List<SalesImportBatchResponse> listBatches(UUID companyId, UUID uploadedBy) {
        if (uploadedBy == null) {
            return jdbcTemplate.query("SELECT " + BATCH_COLUMNS + """
                     FROM sales_import_batches WHERE company_id = ? ORDER BY uploaded_at DESC, id
                    """, BATCH, companyId);
        }
        return jdbcTemplate.query("SELECT " + BATCH_COLUMNS + """
                 FROM sales_import_batches WHERE company_id = ? AND uploaded_by = ? ORDER BY uploaded_at DESC, id
                """, BATCH, companyId, uploadedBy);
    }

    public Optional<SalesImportBatchResponse> findBatch(UUID companyId, UUID batchId) {
        return jdbcTemplate.query("SELECT " + BATCH_COLUMNS + " FROM sales_import_batches WHERE company_id = ? AND id = ?",
                BATCH, companyId, batchId).stream().findFirst();
    }

    // Locks the batch until the transaction ends, so a decision is taken once.
    public Optional<SalesImportBatchResponse> lockBatch(UUID companyId, UUID batchId) {
        return jdbcTemplate.query("SELECT " + BATCH_COLUMNS
                        + " FROM sales_import_batches WHERE company_id = ? AND id = ? FOR UPDATE",
                BATCH, companyId, batchId).stream().findFirst();
    }

    public List<SalesImportRowResponse> rows(UUID batchId) {
        List<SalesImportRowResponse> rows = new ArrayList<>();
        jdbcTemplate.query("""
                        SELECT row_number, sale_date, cash_sales_amount, card_sales_amount, validation_status,
                               duplicate_date, validation_errors::text AS errors, raw_row::text AS raw
                        FROM sales_import_rows WHERE batch_id = ? ORDER BY row_number
                        """,
                rs -> {
                    String raw = rs.getString("raw");
                    rows.add(new SalesImportRowResponse(rs.getInt("row_number"),
                            rs.getObject("sale_date", LocalDate.class), rs.getBigDecimal("cash_sales_amount"),
                            rs.getBigDecimal("card_sales_amount"), rs.getString("validation_status"),
                            rs.getBoolean("duplicate_date"), jsonMapper.readValue(rs.getString("errors"), ERRORS),
                            raw == null ? null : jsonMapper.readValue(raw, RAW)));
                }, batchId);
        return rows;
    }

    /**
     * Valid rows of approved imports with a sale date between from and to, oldest first. Approval
     * guarantees one such row per company and date.
     */
    public List<SalesHistoryResponse.Day> approvedSales(UUID companyId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                        SELECT r.sale_date, r.cash_sales_amount, r.card_sales_amount, b.id AS batch_id
                        FROM sales_import_rows r
                        JOIN sales_import_batches b ON b.id = r.batch_id
                        WHERE b.company_id = ? AND b.status = 'APPROVED' AND r.validation_status = 'VALID'
                          AND r.sale_date BETWEEN ? AND ?
                        ORDER BY r.sale_date
                        """,
                (rs, rowNum) -> new SalesHistoryResponse.Day(rs.getObject("sale_date", LocalDate.class),
                        rs.getBigDecimal("cash_sales_amount"), rs.getBigDecimal("card_sales_amount"),
                        rs.getObject("batch_id", UUID.class)),
                companyId, from, to);
    }

    public List<LocalDate> validDates(UUID batchId) {
        return jdbcTemplate.queryForList("""
                SELECT sale_date FROM sales_import_rows
                WHERE batch_id = ? AND validation_status = 'VALID'
                """, LocalDate.class, batchId);
    }

    /**
     * Records the batch's valid rows as daily sales of the batch's company, entered by the
     * uploader. Days with zero cash and zero card sales are not daily sales records (a saved
     * record must hold some sales); they stay as valid rows of the approved import. Returns the
     * number of daily sales created.
     */
    public int recordApprovedSales(UUID batchId) {
        return jdbcTemplate.update("""
                INSERT INTO daily_sales
                    (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by,
                     import_batch_id)
                SELECT b.company_id, r.sale_date, r.cash_sales_amount, r.card_sales_amount, 'HISTORICAL_IMPORT',
                       b.uploaded_by, b.id
                FROM sales_import_rows r
                JOIN sales_import_batches b ON b.id = r.batch_id
                WHERE b.id = ? AND r.validation_status = 'VALID'
                  AND r.cash_sales_amount + r.card_sales_amount > 0
                """, batchId);
    }

    public boolean markApproved(UUID batchId, int version, UUID approvedBy, String note, int imported, int excluded) {
        return jdbcTemplate.update("""
                UPDATE sales_import_batches SET
                    status = 'APPROVED', approved_by = ?, approved_at = now(), approval_note = ?,
                    imported_row_count = ?, excluded_row_count = ?, version = version + 1
                WHERE id = ? AND status = 'PENDING_APPROVAL' AND version = ?
                """, approvedBy, note, imported, excluded, batchId, version) == 1;
    }

    public boolean markRejected(UUID batchId, int version, UUID rejectedBy, String reason) {
        return jdbcTemplate.update("""
                UPDATE sales_import_batches SET
                    status = 'REJECTED', rejected_by = ?, rejected_at = now(), rejection_reason = ?,
                    version = version + 1
                WHERE id = ? AND status = 'PENDING_APPROVAL' AND version = ?
                """, rejectedBy, reason, batchId, version) == 1;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
