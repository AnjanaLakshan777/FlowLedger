package com.flowledger.cashmanagement.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The import tables' constraints, directly in the database. Each test is rolled back, and a
 * failing statement is always the last one in its test. Needs the local cash-management database.
 */
@SpringBootTest
@Transactional
class SalesImportMigrationTest {

    private static final String SHA = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
    }

    @Test
    void shouldApplyTheImportMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V2__create_sales_import_and_audit_tables.sql");

        assertThat(migration.get("version")).isEqualTo("2");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldLinkImportedSalesOnlyToABatchOfTheSameCompany() {
        UUID batchId = batch(companyId, "PENDING_APPROVAL", SHA);
        insertSale(companyId, "HISTORICAL_IMPORT", batchId);

        assertThatThrownBy(() -> insertSale(UUID.randomUUID(), "HISTORICAL_IMPORT", batchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldNotLinkManualSalesToABatch() {
        UUID batchId = batch(companyId, "PENDING_APPROVAL", SHA);

        assertThatThrownBy(() -> insertSale(companyId, "UI", batchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowTheSameFileOnlyOnceWhileWaitingOrApproved() {
        batch(companyId, "INVALID", SHA);
        batch(companyId, "INVALID", SHA);
        batch(companyId, "PENDING_APPROVAL", SHA);
        batch(UUID.randomUUID(), "PENDING_APPROVAL", SHA);

        assertThatThrownBy(() -> batch(companyId, "PENDING_APPROVAL", SHA)).isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "UPLOADED"})
    void shouldRequireDecisionDetailsForTheStatus(String status) {
        assertThatThrownBy(() -> batch(companyId, status, SHA)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepRowCountsConsistent() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO sales_import_batches (company_id, original_filename, file_sha256, file_size_bytes,
                            status, row_count, valid_row_count, uploaded_by)
                        VALUES (?, 'a.csv', ?, 10, 'PENDING_APPROVAL', 2, 1, ?)
                        """, companyId, SHA, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRequireValuesOnValidRows() {
        UUID batchId = batch(companyId, "PENDING_APPROVAL", SHA);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO sales_import_rows (batch_id, row_number, sale_date, validation_status)
                        VALUES (?, 2, DATE '2025-01-01', 'VALID')
                        """, batchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID batch(UUID company, String status, String sha) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO sales_import_batches (company_id, original_filename, file_sha256, file_size_bytes,
                            status, uploaded_by)
                        VALUES (?, 'a.csv', ?, 10, ?, ?)
                        RETURNING id
                        """, UUID.class, company, sha, status, UUID.randomUUID());
    }

    private void insertSale(UUID company, String source, UUID batchId) {
        jdbcTemplate.update("""
                        INSERT INTO daily_sales (company_id, sale_date, cash_sales_amount, card_sales_amount,
                            entry_source, recorded_by, import_batch_id)
                        VALUES (?, DATE '2025-01-01', 10.00, 0.00, ?, ?, ?)
                        """, company, source, UUID.randomUUID(), batchId);
    }
}
