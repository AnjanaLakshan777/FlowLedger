package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.dto.ManualTransactionRequest;
import com.flowledger.bankintegration.entity.TransactionCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes manually entered bank data with SQL, like {@link StatementImportStore}, since these
 * columns are read-only through JPA.
 */
@Repository
@RequiredArgsConstructor
public class ManualEntryStore {

    private final JdbcTemplate jdbcTemplate;

    // A manual entry is never a duplicate of another, so its fingerprint is unique by construction.
    public UUID insertTransaction(UUID companyId, UUID bankAccountId, String currency, ManualTransactionRequest entry,
                                  TransactionCategory category, String otherDescription, UUID actor, Instant now,
                                  String fingerprint) {
        boolean classified = category != TransactionCategory.UNCLASSIFIED;
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_transactions
                            (company_id, bank_account_id, deduplication_fingerprint, transaction_date, direction,
                             amount, currency, description, cheque_number, category, other_description,
                             classification_method, classified_by, classified_at, entry_source, entered_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, fingerprint, entry.transactionDate(), entry.direction().name(),
                entry.amount(), currency, entry.description().strip(), entry.chequeNumber(), category.name(),
                otherDescription,
                classified ? "MANUAL" : "UNKNOWN", classified ? actor : null,
                classified ? Timestamp.from(now) : null, actor);
    }

    // Empty if the same balance at the same time was already recorded.
    public Optional<UUID> insertBalanceSnapshot(UUID companyId, UUID bankAccountId, String currency,
                                                BigDecimal balance, OffsetDateTime balanceAsOf) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                        INSERT INTO bank_balance_snapshots
                            (company_id, bank_account_id, balance_amount, currency, balance_as_of, source_type)
                        VALUES (?, ?, ?, ?, ?, 'MANUAL')
                        ON CONFLICT (bank_account_id, balance_as_of, balance_amount) DO NOTHING
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, balance, currency, balanceAsOf);
        return ids.stream().findFirst();
    }

    // Only manual entries can be removed; imported transactions are the bank's record.
    public boolean deleteManualTransaction(UUID companyId, UUID transactionId) {
        return jdbcTemplate.update(
                "DELETE FROM bank_transactions WHERE company_id = ? AND id = ? AND entry_source = 'MANUAL'",
                companyId, transactionId) == 1;
    }
}
