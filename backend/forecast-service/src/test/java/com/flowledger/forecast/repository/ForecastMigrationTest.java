package com.flowledger.forecast.repository;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the forecast tables and their constraints directly, independent of the application.
 * Needs the local forecast PostgreSQL database. Each test is rolled back, and a failing statement
 * is always the last one in its test.
 */
@SpringBootTest
@Transactional
class ForecastMigrationTest {

    private static final String HASH = "b".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID configId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        configId = insertConfig(1, true);
    }

    @Test
    void shouldApplyForecastMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V1__create_forecast_tables.sql");

        assertThat(migration.get("version")).isEqualTo("1");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldAllowOnlyOneActiveConfigPerCompany() {
        insertConfig(2, false);

        assertThatThrownBy(() -> insertConfig(3, true)).isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest(name = "horizon {0}, minimum balance {1}")
    @CsvSource({"90, 0.00", "84, 1000.00"})
    void shouldKeepTheConfirmedHorizonAndShortageThreshold(int horizonDays, String minimumBalance) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO forecast_model_configs (company_id, version, horizon_days, minimum_balance,
                                                            method_code, created_by)
                        VALUES (?, 9, ?, ?::numeric, 'HISTORICAL_BANK_CASH_FLOW_PATTERN', ?)
                        """,
                companyId, horizonDays, minimumBalance, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRequireAnEightyFourDayHorizon() {
        assertThatThrownBy(() -> insertRun("2026-10-15", "2027-01-07", HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldSaveTheSameInputsOnlyOncePerCompany() {
        insertRun("2026-10-15", "2027-01-06", HASH);

        assertThatThrownBy(() -> insertRun("2026-10-15", "2027-01-06", HASH))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldAcceptAConsistentDay() {
        UUID runId = insertRun("2026-10-15", "2027-01-06", HASH);

        insertDay(runId, "100.00", "50.00", "150.00", "400.00", "1000.00", "750.00", false);
        insertDay(runId, null, null, null, "800.00", "750.00", "-50.00", true, "2026-10-16");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM forecast_daily_results WHERE forecast_run_id = ?", Integer.class, runId))
                .isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "inflow sum, 100.00, 50.00, 100.00, 0.00, 1000.00, 1100.00, false",
            "closing balance, 100.00, 50.00, 150.00, 0.00, 1000.00, 1000.00, false",
            "shortage flag, 0.00, 0.00, 0.00, 2000.00, 1000.00, -1000.00, false",
            "zero is no shortage, 0.00, 0.00, 0.00, 1000.00, 1000.00, 0.00, true",
            "partly missing inflows, NULL, 50.00, NULL, 0.00, 1000.00, 1050.00, false",
            "negative inflow, -10.00, 0.00, -10.00, 0.00, 1000.00, 990.00, false"
    })
    void shouldRejectInconsistentDays(String rule, String cash, String card, String sales, String cheques,
                                      String opening, String closing, boolean shortage) {
        UUID runId = insertRun("2026-10-15", "2027-01-06", HASH);

        assertThatThrownBy(() -> insertDay(runId, cash, card, sales, cheques, opening, closing, shortage))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepSavedRunsImmutable() {
        UUID runId = insertRun("2026-10-15", "2027-01-06", HASH);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE forecast_runs SET opening_balance_amount = 1 WHERE id = ?", runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("forecast_runs is append-only");
    }

    @Test
    void shouldKeepSavedDaysImmutable() {
        UUID runId = insertRun("2026-10-15", "2027-01-06", HASH);
        insertDay(runId, "0.00", "0.00", "0.00", "0.00", "1000.00", "1000.00", false);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM forecast_daily_results WHERE forecast_run_id = ?", runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("forecast_daily_results is append-only");
    }

    @Test
    void shouldRejectARunForAnotherCompanysConfig() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO forecast_runs (company_id, config_id, bank_account_id, run_status, trigger_type,
                            as_of_at, horizon_start_date, horizon_end_date, opening_balance_amount,
                            opening_balance_as_of, balance_stale, inflow_estimate_status, model_version,
                            config_snapshot, completed_at)
                        VALUES (?, ?, ?, 'SUCCEEDED', 'MANUAL', now(), DATE '2026-10-15', DATE '2027-01-06', 0,
                            now(), false, 'ESTIMATED', '1', '{}', now())
                        """, UUID.randomUUID(), configId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldApplyChequeProjectionMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V2__create_cheque_projection_and_processed_events.sql");

        assertThat(migration.get("version")).isEqualTo("2");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @ParameterizedTest(name = "number {0}, amount {1}, status {2}, version {3}, clearing {4}")
    @CsvSource({
            "12A, 10.00, ISSUED, 1, 2026-10-20",
            "123, 0.00, ISSUED, 1, 2026-10-20",
            "123, 10.00, CLEARED, 1, 2026-10-20",
            "123, 10.00, ISSUED, 0, 2026-10-20",
            "123, 10.00, ISSUED, 1, 2026-10-14"
    })
    void shouldRejectInvalidProjectedCheques(String number, String amount, String status, int version,
                                             String clearingDate) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO forecast_cheques (cheque_id, company_id, bank_account_id, cheque_number, category,
                            amount, issue_date, expected_clearing_date, status, source_version, last_event_id)
                        VALUES (?, ?, ?, ?, 'TAX', ?::numeric, DATE '2026-10-15', ?::date, ?, ?, ?)
                        """,
                UUID.randomUUID(), companyId, UUID.randomUUID(), number, amount, clearingDate, status, version,
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRecordAnEventIdOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        String insert = """
                INSERT INTO processed_events (event_id, source_service, event_type)
                VALUES (?, 'payment-service', 'ChequeIssued')
                """;
        jdbcTemplate.update(insert, eventId);

        assertThatThrownBy(() -> jdbcTemplate.update(insert, eventId)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldApplyChequeBackfillMigration() {
        Map<String, Object> migration = jdbcTemplate.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE script = ?",
                "V3__add_cheque_backfill_state.sql");

        assertThat(migration.get("version")).isEqualTo("3");
        assertThat(migration.get("success")).isEqualTo(true);
    }

    @Test
    void shouldStoreBackfilledChequesWithoutAnEventId() {
        UUID chequeId = UUID.randomUUID();
        insertProjectedCheque(chequeId, "BACKFILL", null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT projection_source FROM forecast_cheques WHERE cheque_id = ?", String.class, chequeId))
                .isEqualTo("BACKFILL");
    }

    @ParameterizedTest(name = "source {0}, with event ID {1}")
    @CsvSource({"EVENT, false", "BACKFILL, true", "IMPORT, false"})
    void shouldKeepTheEventIdConsistentWithTheSource(String source, boolean withEventId) {
        assertThatThrownBy(() -> insertProjectedCheque(UUID.randomUUID(), source,
                withEventId ? UUID.randomUUID() : null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldReadExistingEventRowsAsEventSourced() {
        UUID chequeId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO forecast_cheques (cheque_id, company_id, bank_account_id, cheque_number, category,
                            amount, issue_date, expected_clearing_date, status, source_version, last_event_id)
                        VALUES (?, ?, ?, '123', 'TAX', 10.00, DATE '2026-10-15', DATE '2026-10-20', 'ISSUED', 1, ?)
                        """,
                chequeId, companyId, UUID.randomUUID(), UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT projection_source FROM forecast_cheques WHERE cheque_id = ?", String.class, chequeId))
                .isEqualTo("EVENT");
    }

    @Test
    void shouldAllowOnlyOneBackfillRunInProgress() {
        jdbcTemplate.update("DELETE FROM cheque_backfill_rejections WHERE backfill_run_id IN "
                + "(SELECT id FROM cheque_backfill_runs WHERE status = 'IN_PROGRESS')");
        jdbcTemplate.update("DELETE FROM cheque_backfill_runs WHERE status = 'IN_PROGRESS'");
        jdbcTemplate.update("INSERT INTO cheque_backfill_runs (status, completed_at) VALUES ('COMPLETED', now())");
        jdbcTemplate.update("INSERT INTO cheque_backfill_runs (status, completed_at) VALUES ('COMPLETED', now())");
        jdbcTemplate.update("INSERT INTO cheque_backfill_runs (status) VALUES ('IN_PROGRESS')");

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO cheque_backfill_runs (status) VALUES ('IN_PROGRESS')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "completed without time | INSERT INTO cheque_backfill_runs (status) VALUES ('COMPLETED')",
            "in progress with completion time | INSERT INTO cheque_backfill_runs (status, completed_at) VALUES ('IN_PROGRESS', now())",
            "counts do not add up | INSERT INTO cheque_backfill_runs (status, cheques_seen, applied_count) VALUES ('IN_PROGRESS', 2, 1)",
            "unknown status | INSERT INTO cheque_backfill_runs (status) VALUES ('VERIFIED')"
    })
    void shouldRejectInconsistentBackfillRuns(String description, String insert) {
        jdbcTemplate.update("DELETE FROM cheque_backfill_rejections WHERE backfill_run_id IN "
                + "(SELECT id FROM cheque_backfill_runs WHERE status = 'IN_PROGRESS')");
        jdbcTemplate.update("DELETE FROM cheque_backfill_runs WHERE status = 'IN_PROGRESS'");

        assertThatThrownBy(() -> jdbcTemplate.update(insert)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRequireAnExistingRunForARejection() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cheque_backfill_rejections (backfill_run_id, reason) VALUES (?, 'Invalid amount')",
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertProjectedCheque(UUID chequeId, String source, UUID eventId) {
        jdbcTemplate.update("""
                        INSERT INTO forecast_cheques (cheque_id, company_id, bank_account_id, cheque_number, category,
                            amount, issue_date, expected_clearing_date, status, source_version, last_event_id,
                            projection_source)
                        VALUES (?, ?, ?, '123', 'TAX', 10.00, DATE '2026-10-15', DATE '2026-10-20', 'ISSUED', 1, ?, ?)
                        """,
                chequeId, companyId, UUID.randomUUID(), eventId, source);
    }

    private UUID insertConfig(int version, boolean active) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO forecast_model_configs (company_id, version, is_active, horizon_days, method_code,
                                                            created_by)
                        VALUES (?, ?, ?, 84, 'HISTORICAL_BANK_CASH_FLOW_PATTERN', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, version, active, UUID.randomUUID());
    }

    private UUID insertRun(String start, String end, String hash) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO forecast_runs (company_id, config_id, bank_account_id, run_status, trigger_type,
                            as_of_at, horizon_start_date, horizon_end_date, opening_balance_amount,
                            opening_balance_as_of, balance_stale, inflow_estimate_status, model_version,
                            config_snapshot, input_snapshot_hash, completed_at)
                        VALUES (?, ?, ?, 'SUCCEEDED', 'MANUAL', now(), ?::date, ?::date, 1000.00, now(), false,
                            'ESTIMATED', '1:WEEKDAY_AVERAGE', '{}', ?, now())
                        RETURNING id
                        """,
                UUID.class, companyId, configId, UUID.randomUUID(), start, end, hash);
    }

    private void insertDay(UUID runId, String cash, String card, String sales, String cheques, String opening,
                           String closing, boolean shortage) {
        insertDay(runId, cash, card, sales, cheques, opening, closing, shortage, "2026-10-15");
    }

    private void insertDay(UUID runId, String cash, String card, String sales, String cheques, String opening,
                           String closing, boolean shortage, String date) {
        jdbcTemplate.update("""
                        INSERT INTO forecast_daily_results (forecast_run_id, forecast_date,
                            expected_cash_sales_deposits_amount, expected_card_sales_settlements_amount,
                            expected_sales_inflows_amount, expected_cheque_outflows_amount, opening_balance_amount,
                            closing_balance_amount, is_cash_shortage)
                        VALUES (?, ?::date, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?)
                        """,
                runId, date, cash, card, sales, cheques, opening, closing, shortage);
    }
}
