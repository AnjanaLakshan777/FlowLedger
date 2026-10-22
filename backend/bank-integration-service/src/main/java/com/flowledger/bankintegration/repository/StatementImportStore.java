package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.statement.StatementLine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inserts imported rows with ON CONFLICT DO NOTHING. A row already imported (same account and
 * fingerprint) is skipped instead of failing the whole import, and a concurrent import of the
 * same rows waits for the other transaction and then skips them, so nothing is stored twice.
 */
@Repository
@RequiredArgsConstructor
public class StatementImportStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns the new transaction's ID, or empty if it was already imported. A row that carries its
     * category (the monthly template) is stored as classified by the importing user.
     */
    public Optional<UUID> insertTransaction(UUID companyId, UUID bankAccountId, String currency, UUID batchId,
                                            StatementLine line, String fingerprint, UUID actor, Instant now) {
        boolean classified = line.isClassified();
        List<UUID> ids = jdbcTemplate.queryForList("""
                        INSERT INTO bank_transactions
                            (company_id, bank_account_id, deduplication_fingerprint, transaction_date, direction,
                             amount, currency, description, running_balance, cheque_number, branch_code,
                             source_import_batch_id, category, other_description, classification_method,
                             classified_by, classified_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (bank_account_id, deduplication_fingerprint) DO NOTHING
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, fingerprint, line.transactionDate(), line.direction().name(),
                line.amount(), currency, line.description(), line.runningBalance(), line.chequeNumber(),
                line.branchCode(), batchId, line.category().name(), line.otherDescription(),
                classified ? "MANUAL" : "UNKNOWN", classified ? actor : null,
                classified ? Timestamp.from(now) : null);
        return ids.stream().findFirst();
    }

    // Serializes monthly imports of one account, so two uploads of the same month cannot both pass the overlap check.
    public void lockAccountImports(UUID bankAccountId) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "bank-statement-import:" + bankAccountId);
    }

    // File names of imported (not removed or failed) statements whose period overlaps from..to;
    // with a format, only those of that format.
    public List<String> importedOverlapping(UUID companyId, UUID bankAccountId, LocalDate from, LocalDate to,
                                            String format) {
        return jdbcTemplate.queryForList("""
                SELECT filename FROM bank_statement_import_batches
                WHERE company_id = ? AND bank_account_id = ? AND status = 'IMPORTED'
                  AND period_start <= ? AND period_end >= ? AND (?::text IS NULL OR file_format = ?)
                ORDER BY period_start
                """, String.class, companyId, bankAccountId, to, from, format, format);
    }

    // The closing balance an imported statement recorded for the end of that day, if one ends there.
    public Optional<BigDecimal> importedClosingBalance(UUID companyId, UUID bankAccountId, LocalDate day) {
        return jdbcTemplate.queryForList("""
                SELECT s.balance_amount FROM bank_balance_snapshots s
                JOIN bank_statement_import_batches b ON b.company_id = s.company_id AND b.id = s.source_import_batch_id
                WHERE s.company_id = ? AND s.bank_account_id = ? AND b.status = 'IMPORTED' AND b.period_end = ?
                ORDER BY s.balance_as_of DESC, s.created_at DESC
                LIMIT 1
                """, BigDecimal.class, companyId, bankAccountId, day).stream().findFirst();
    }

    // Removing an import deletes only the rows it added; rows it found already imported belong to an
    // earlier import and stay.
    public int deleteBatchTransactions(UUID companyId, UUID batchId) {
        return jdbcTemplate.update(
                "DELETE FROM bank_transactions WHERE company_id = ? AND source_import_batch_id = ?", companyId, batchId);
    }

    public int deleteBatchBalanceSnapshots(UUID companyId, UUID batchId) {
        return jdbcTemplate.update(
                "DELETE FROM bank_balance_snapshots WHERE company_id = ? AND source_import_batch_id = ?", companyId, batchId);
    }

    // Returns false if the same balance at the same time was already recorded.
    public boolean insertBalanceSnapshot(UUID companyId, UUID bankAccountId, String currency, BigDecimal balance,
                                         OffsetDateTime balanceAsOf, UUID batchId) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                        INSERT INTO bank_balance_snapshots
                            (company_id, bank_account_id, balance_amount, currency, balance_as_of, source_type,
                             source_import_batch_id)
                        VALUES (?, ?, ?, ?, ?, 'STATEMENT_IMPORT', ?)
                        ON CONFLICT (bank_account_id, balance_as_of, balance_amount) DO NOTHING
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, balance, currency, balanceAsOf, batchId);
        return !ids.isEmpty();
    }
}
