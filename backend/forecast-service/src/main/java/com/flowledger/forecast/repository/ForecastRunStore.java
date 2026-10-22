package com.flowledger.forecast.repository;

import com.flowledger.forecast.calculation.DailyProjection;
import com.flowledger.forecast.calculation.ExpectedCheque;
import com.flowledger.forecast.calculation.ExpectedInflow;
import com.flowledger.forecast.calculation.InflowEstimateStatus;
import com.flowledger.forecast.entity.DataQuality;
import com.flowledger.forecast.entity.TriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Inserts a complete forecast run. The run row uses ON CONFLICT DO NOTHING on the input hash, so
 * the same inputs requested twice at the same time are saved once: the second request waits for
 * the first transaction and then finds its run.
 */
@Repository
@RequiredArgsConstructor
public class ForecastRunStore {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public record NewRun(
            UUID companyId,
            UUID configId,
            UUID bankAccountId,
            Instant asOfAt,
            LocalDate horizonStart,
            LocalDate horizonEnd,
            BigDecimal openingBalance,
            Instant openingBalanceAsOf,
            String currency,
            boolean balanceStale,
            InflowEstimateStatus inflowEstimateStatus,
            DataQuality dataQuality,
            String modelVersion,
            Map<String, Object> configSnapshot,
            String inputSnapshotHash,
            TriggerType triggerType,
            // The user who asked for the run; null for a scheduled run.
            UUID triggeredBy,
            Instant completedAt
    ) {
    }

    // Returns the new run's ID, or empty if a run with the same inputs already exists.
    public Optional<UUID> insertRun(NewRun run) {
        List<UUID> ids = jdbcTemplate.queryForList("""
                        INSERT INTO forecast_runs
                            (company_id, config_id, bank_account_id, run_status, trigger_type, as_of_at,
                             horizon_start_date, horizon_end_date, opening_balance_amount, opening_balance_as_of,
                             currency, balance_stale, inflow_estimate_status, data_quality, model_version,
                             config_snapshot, input_snapshot_hash, triggered_by, completed_at)
                        VALUES (?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                        ON CONFLICT (company_id, input_snapshot_hash) WHERE input_snapshot_hash IS NOT NULL
                        DO NOTHING
                        RETURNING id
                        """,
                UUID.class, run.companyId(), run.configId(), run.bankAccountId(), run.triggerType().name(), utc(run.asOfAt()),
                run.horizonStart(), run.horizonEnd(), run.openingBalance(), utc(run.openingBalanceAsOf()),
                run.currency(), run.balanceStale(), run.inflowEstimateStatus().name(),
                jsonMapper.writeValueAsString(run.dataQuality()), run.modelVersion(),
                jsonMapper.writeValueAsString(run.configSnapshot()), run.inputSnapshotHash(), run.triggeredBy(),
                utc(run.completedAt()));
        return ids.stream().findFirst();
    }

    public void insertDays(UUID runId, List<DailyProjection> days) {
        jdbcTemplate.batchUpdate("""
                        INSERT INTO forecast_daily_results
                            (forecast_run_id, forecast_date, expected_cash_sales_deposits_amount,
                             expected_card_sales_settlements_amount, expected_sales_inflows_amount,
                             expected_cheque_outflows_amount, expected_cheque_count, opening_balance_amount,
                             closing_balance_amount, is_cash_shortage)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                days.stream().map(day -> {
                    ExpectedInflow inflow = day.inflow();
                    return new Object[]{runId, day.date(),
                            inflow == null ? null : inflow.cashSalesDeposits(),
                            inflow == null ? null : inflow.cardSalesSettlements(),
                            inflow == null ? null : inflow.total(),
                            day.chequeOutflows(), day.chequeCount(), day.openingBalance(), day.closingBalance(),
                            day.cashShortage()};
                }).toList());
    }

    public void insertCheques(UUID runId, List<ExpectedCheque> cheques) {
        jdbcTemplate.batchUpdate("""
                        INSERT INTO forecast_run_cheques
                            (forecast_run_id, cheque_id, cheque_number, amount, expected_clearing_date, status_at_run,
                             source_version)
                        VALUES (?, ?, ?, ?, ?, 'ISSUED', ?)
                        """,
                cheques.stream()
                        .map(cheque -> new Object[]{runId, cheque.chequeId(), cheque.chequeNumber(), cheque.amount(),
                                cheque.expectedClearingDate(), cheque.sourceVersion()})
                        .toList());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
