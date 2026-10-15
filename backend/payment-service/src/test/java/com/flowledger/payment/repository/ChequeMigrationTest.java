package com.flowledger.payment.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the Flyway migration and the database constraints directly, independent of the API
 * validation in front of them. Needs the local payment PostgreSQL database. Each test is rolled
 * back, and a failing statement is always the last one in its test.
 */
@SpringBootTest
@Transactional
class ChequeMigrationTest {

    private static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 15);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID bankAccountId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();
    }

    @Test
    void shouldApplyChequeMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?", "V1__create_cheque_tables.sql");

        assertThat(migration.get("version")).isEqualTo("1");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldApplyDefaultsAndPreserveLeadingZeros() {
        UUID id = insert("000042", "SUPPLIER_PAYMENT", "10.00", ISSUE_DATE.plusDays(5), "ISSUED");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM cheques WHERE id = ?", id);
        assertThat(row.get("cheque_number")).isEqualTo("000042");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_by")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "non-digit cheque number, 12A456, SUPPLIER_PAYMENT, 10.00, 5, ISSUED",
            "unknown category,        000001, RENT,             10.00, 5, ISSUED",
            "zero amount,             000001, SUPPLIER_PAYMENT, 0.00,  5, ISSUED",
            "clearing before issue,   000001, SUPPLIER_PAYMENT, 10.00, -1, ISSUED",
            "paid status,             000001, SUPPLIER_PAYMENT, 10.00, 5, PAID"
    })
    void shouldRejectValuesOutsideConstraints(String rule, String number, String category, String amount,
                                              int clearingOffset, String status) {
        assertThatThrownBy(() -> insert(number, category, amount, ISSUE_DATE.plusDays(clearingOffset), status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowEachChequeNumberOncePerCompanyAccount() {
        insert("000001", "SALARY", "10.00", ISSUE_DATE.plusDays(5), "ISSUED");

        assertThatThrownBy(() -> insert("000001", "TAX", "20.00", ISSUE_DATE.plusDays(6), "CANCELLED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_cheques_company_account_number");
    }

    @Test
    void shouldRejectUpdatesToAuditHistory() {
        UUID chequeId = insert("000001", "SALARY", "10.00", ISSUE_DATE.plusDays(5), "ISSUED");
        insertAudit(chequeId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payment_audit_events SET reason = 'changed' WHERE cheque_id = ?", chequeId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payment_audit_events is append-only");
    }

    @Test
    void shouldRejectDeletesFromAuditHistory() {
        UUID chequeId = insert("000001", "SALARY", "10.00", ISSUE_DATE.plusDays(5), "ISSUED");
        insertAudit(chequeId);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payment_audit_events WHERE cheque_id = ?", chequeId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payment_audit_events is append-only");
    }

    @Test
    void shouldRejectTruncatingAuditHistory() {
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE payment_audit_events"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payment_audit_events is append-only");
    }

    @Test
    void shouldRejectAuditEventForAnotherCompanysCheque() {
        UUID chequeId = insert("000001", "SALARY", "10.00", ISSUE_DATE.plusDays(5), "ISSUED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO payment_audit_events (company_id, action, cheque_id) VALUES (?, 'CHEQUE_ISSUED', ?)
                """, UUID.randomUUID(), chequeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insert(String number, String category, String amount, LocalDate clearingDate, String status) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO cheques (company_id, bank_account_id, cheque_number, payee_name, category, amount,
                                             issue_date, expected_clearing_date, status, created_by)
                        VALUES (?, ?, ?, 'Payee', ?, ?::numeric, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, number, category, amount, ISSUE_DATE, clearingDate, status,
                UUID.randomUUID());
    }

    private void insertAudit(UUID chequeId) {
        jdbcTemplate.update("INSERT INTO payment_audit_events (company_id, action, cheque_id) VALUES (?, 'CHEQUE_ISSUED', ?)",
                companyId, chequeId);
    }
}
