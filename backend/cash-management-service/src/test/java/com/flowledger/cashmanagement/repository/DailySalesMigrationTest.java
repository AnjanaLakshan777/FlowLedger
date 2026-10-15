package com.flowledger.cashmanagement.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the Flyway migration and the database constraints directly, independent of the API
 * validation in front of them. Needs the local cash-management PostgreSQL database.
 * Each test is rolled back, and a failing statement is always the last one in its test.
 */
@SpringBootTest
@Transactional
class DailySalesMigrationTest {

    private static final LocalDate SALE_DATE = LocalDate.of(2026, 10, 3);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
    }

    @Test
    void shouldApplyDailySalesMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V1__create_daily_sales_table.sql");

        assertThat(migration.get("version")).isEqualTo("1");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldApplyDatabaseDefaults() {
        UUID id = insert(companyId, SALE_DATE, "50000.00", "0.00", "UI");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM daily_sales WHERE id = ?", id);
        assertThat(row.get("recorded_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("updated_by")).isNull();
        assertThat(row.get("updated_at")).isNull();
    }

    @Test
    void shouldStoreAmountsWithTwoDecimalPlaces() {
        UUID id = insert(companyId, SALE_DATE, "1234.5", "0.25", "UI");

        BigDecimal cash = jdbcTemplate.queryForObject(
                "SELECT cash_sales_amount FROM daily_sales WHERE id = ?", BigDecimal.class, id);
        assertThat(cash).isEqualTo(new BigDecimal("1234.50"));
    }

    @Test
    void shouldRejectNegativeAmount() {
        assertThatThrownBy(() -> insert(companyId, SALE_DATE, "-1.00", "100.00", "UI"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectRecordWithoutAnySales() {
        assertThatThrownBy(() -> insert(companyId, SALE_DATE, "0.00", "0.00", "UI"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_sales_total_positive");
    }

    @Test
    void shouldRejectImportedRowsUntilImportFeatureExists() {
        assertThatThrownBy(() -> insert(companyId, SALE_DATE, "100.00", "0.00", "HISTORICAL_IMPORT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowOnlyOneRecordPerCompanyAndSaleDate() {
        insert(companyId, SALE_DATE, "100.00", "0.00", "UI");

        assertThatThrownBy(() -> insert(companyId, SALE_DATE, "200.00", "0.00", "UI"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_daily_sales_company_sale_date");
    }

    @Test
    void shouldAllowSameSaleDateForDifferentCompanies() {
        insert(companyId, SALE_DATE, "100.00", "0.00", "UI");
        insert(UUID.randomUUID(), SALE_DATE, "200.00", "0.00", "UI");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE sale_date = ? AND company_id = ?",
                Integer.class, SALE_DATE, companyId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldRequireUpdateActorAndTimeTogether() {
        UUID id = insert(companyId, SALE_DATE, "100.00", "0.00", "UI");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE daily_sales SET updated_by = ? WHERE id = ?", UUID.randomUUID(), id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_sales_update_actor_time_pair");
    }

    private UUID insert(UUID companyId, LocalDate saleDate, String cash, String card, String entrySource) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO daily_sales
                            (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, companyId, saleDate, new BigDecimal(cash), new BigDecimal(card), entrySource,
                UUID.randomUUID());
    }
}
