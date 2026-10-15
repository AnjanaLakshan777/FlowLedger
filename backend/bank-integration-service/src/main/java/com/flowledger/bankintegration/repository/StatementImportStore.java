package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.statement.StatementLine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    // Returns the new transaction's ID, or empty if it was already imported.
    public Optional<UUID> insertTransaction(UUID companyId, UUID bankAccountId, String currency, UUID batchId,
                                            StatementLine line, String fingerprint) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                        INSERT INTO bank_transactions
                            (company_id, bank_account_id, deduplication_fingerprint, transaction_date, direction,
                             amount, currency, description, running_balance, cheque_number, branch_code,
                             source_import_batch_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (bank_account_id, deduplication_fingerprint) DO NOTHING
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, fingerprint, line.transactionDate(), line.direction().name(),
                line.amount(), currency, line.description(), line.runningBalance(), line.chequeNumber(),
                line.branchCode(), batchId);
        return ids.stream().findFirst();
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
