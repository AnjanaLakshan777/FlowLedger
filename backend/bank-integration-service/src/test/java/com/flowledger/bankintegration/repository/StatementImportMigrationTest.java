package com.flowledger.bankintegration.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the statement import tables and their constraints directly, independent of the API
 * validation in front of them. Needs the local bank-integration PostgreSQL database.
 * Each test is rolled back, and a failing statement is always the last one in its test.
 */
@SpringBootTest
@Transactional
class StatementImportMigrationTest {

    private static final String FINGERPRINT = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountId = jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Commercial Bank of Ceylon', 'Current', 'CURRENT', 'STATEMENT_IMPORT', 'ACTIVE')
                        RETURNING id
                        """,
                UUID.class, companyId);
    }

    @Test
    void shouldApplyStatementImportMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V2__create_bank_statement_import_tables.sql");

        assertThat(migration.get("version")).isEqualTo("2");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldDefaultNewTransactionsToUnclassified() {
        UUID id = insertTransaction(FINGERPRINT, "CREDIT", "100.00", "UNCLASSIFIED");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM bank_transactions WHERE id = ?", id);
        assertThat(row.get("classification_method")).isEqualTo("UNKNOWN");
        assertThat(row.get("currency")).isEqualTo("LKR");
        assertThat(row.get("entry_sequence")).isNotNull();
    }

    @ParameterizedTest(name = "{1} as {0}")
    @CsvSource({
            "DEBIT, CASH_SALES_DEPOSIT",
            "DEBIT, CARD_SALES_SETTLEMENT",
            "CREDIT, CHEQUE_PAYMENT",
            "CREDIT, SALES"
    })
    void shouldRejectCategoriesThatDoNotFitTheDirection(String direction, String category) {
        assertThatThrownBy(() -> insertTransaction(FINGERPRINT, direction, "100.00", category))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({"0.00", "-5.00"})
    void shouldRejectAmountsThatAreNotPositive(String amount) {
        assertThatThrownBy(() -> insertTransaction(FINGERPRINT, "DEBIT", amount, "UNCLASSIFIED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectTheSameFingerprintTwiceForAnAccount() {
        insertTransaction(FINGERPRINT, "DEBIT", "100.00", "UNCLASSIFIED");

        assertThatThrownBy(() -> insertTransaction(FINGERPRINT, "DEBIT", "100.00", "UNCLASSIFIED"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldRejectChequeNumbersThatAreNotDigits() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO bank_transactions (company_id, bank_account_id, deduplication_fingerprint,
                                                       transaction_date, direction, amount, cheque_number)
                        VALUES (?, ?, ?, DATE '2026-09-01', 'DEBIT', 10, '12A')
                        """, companyId, accountId, FINGERPRINT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectTransactionsForAnAccountOfAnotherCompany() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO bank_transactions (company_id, bank_account_id, deduplication_fingerprint,
                                                       transaction_date, direction, amount)
                        VALUES (?, ?, ?, DATE '2026-09-01', 'DEBIT', 10)
                        """, UUID.randomUUID(), accountId, FINGERPRINT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectBatchCountsAboveTheRowCount() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO bank_statement_import_batches (company_id, bank_account_id, filename, file_sha256,
                                                                   file_format, status, row_count, imported_row_count,
                                                                   duplicate_row_count, imported_by)
                        VALUES (?, ?, 'statement.csv', ?, 'TRANSACTION_DETAILS_CSV', 'IMPORTED', 5, 3, 3, ?)
                        """, companyId, accountId, FINGERPRINT, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowNegativeBalanceButNotTheSameSnapshotTwice() {
        insertSnapshot("-2500.00");

        assertThat(jdbcTemplate.queryForObject("SELECT balance_amount FROM bank_balance_snapshots WHERE company_id = ?",
                BigDecimal.class, companyId)).isEqualByComparingTo("-2500.00");
        assertThatThrownBy(() -> insertSnapshot("-2500.00")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldKeepAuditEventsAppendOnly() {
        UUID id = jdbcTemplate.queryForObject("""
                        INSERT INTO bank_integration_audit_events (company_id, actor_type, action, entity_type, entity_id)
                        VALUES (?, 'USER', 'TRANSACTION_CLASSIFIED', 'BANK_TRANSACTION', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bank_integration_audit_events SET reason = 'changed' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void shouldRejectDeletingAuditEvents() {
        jdbcTemplate.update("""
                        INSERT INTO bank_integration_audit_events (company_id, actor_type, action, entity_type, entity_id)
                        VALUES (?, 'USER', 'BANK_STATEMENT_IMPORTED', 'BANK_STATEMENT_IMPORT', ?)
                        """,
                companyId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM bank_integration_audit_events WHERE company_id = ?", companyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private UUID insertTransaction(String fingerprint, String direction, String amount, String category) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_transactions (company_id, bank_account_id, deduplication_fingerprint,
                                                       transaction_date, direction, amount, category)
                        VALUES (?, ?, ?, DATE '2026-09-01', ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, companyId, accountId, fingerprint, direction, new BigDecimal(amount), category);
    }

    private void insertSnapshot(String balance) {
        jdbcTemplate.update("""
                        INSERT INTO bank_balance_snapshots (company_id, bank_account_id, balance_amount, balance_as_of,
                                                            source_type)
                        VALUES (?, ?, ?, TIMESTAMPTZ '2026-09-30 23:59:59+05:30', 'STATEMENT_IMPORT')
                        """,
                companyId, accountId, new BigDecimal(balance));
    }
}
