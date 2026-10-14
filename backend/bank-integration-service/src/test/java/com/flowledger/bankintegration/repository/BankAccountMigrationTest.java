package com.flowledger.bankintegration.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the Flyway migration and the database constraints directly, independent of the API
 * validation in front of them. Needs the local bank-integration PostgreSQL database.
 * Each test is rolled back, and a failing statement is always the last one in its test.
 */
@SpringBootTest
@Transactional
class BankAccountMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
    }

    @Test
    void shouldApplyBankAccountsMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V1__create_bank_accounts_table.sql");

        assertThat(migration.get("version")).isEqualTo("1");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldApplyDatabaseDefaults() {
        UUID id = insert("CURRENT", "1234", "MANUAL", "ACTIVE");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM bank_accounts WHERE id = ?", id);
        assertThat(row.get("currency")).isEqualTo("LKR");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "account_type, SAVINGS",
            "integration_mode, EMAIL",
            "status, CLOSED",
            "account_number_last4, 12ab"
    })
    void shouldRejectValuesOutsideCheckConstraints(String column, String value) {
        String accountType = column.equals("account_type") ? value : "CURRENT";
        String last4 = column.equals("account_number_last4") ? value : "1234";
        String mode = column.equals("integration_mode") ? value : "MANUAL";
        String status = column.equals("status") ? value : "ACTIVE";

        assertThatThrownBy(() -> insert(accountType, last4, mode, status))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowOnlyOneActiveCurrentAccountPerCompany() {
        insert("CURRENT", "1111", "MANUAL", "ACTIVE");
        insert("CURRENT", "2222", "MANUAL", "INACTIVE");
        insert("CURRENT", "3333", "MANUAL", "INACTIVE");

        assertThatThrownBy(() -> insert("CURRENT", "4444", "MANUAL", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_bank_accounts_one_active_current");
    }

    @Test
    void shouldAllowActiveCurrentAccountsForDifferentCompanies() {
        insert("CURRENT", "1111", "MANUAL", "ACTIVE");
        UUID otherCompany = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                VALUES (?, 'Sampath Bank', 'Other Company', 'CURRENT', 'MANUAL', 'ACTIVE')
                """, otherCompany);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_accounts WHERE company_id IN (?, ?)", Integer.class, companyId, otherCompany);
        assertThat(count).isEqualTo(2);
    }

    private UUID insert(String accountType, String last4, String integrationMode, String status) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts
                            (company_id, bank_name, account_name, account_type, account_number_last4,
                             integration_mode, status)
                        VALUES (?, 'Commercial Bank of Ceylon', 'ABC Trading (Pvt) Ltd', ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, companyId, accountType, last4, integrationMode, status);
    }
}
