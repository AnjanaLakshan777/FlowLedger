package com.flowledger.forecast.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Forecast configurations for tests. Companies created by tests get PREVIOUS_YEAR_PATTERN on their
 * first run; tests of the WEEKDAY_AVERAGE method (kept for older configurations and their runs)
 * give the company a version-1 configuration exactly like those created before Option B.
 */
public final class ForecastConfigs {

    private ForecastConfigs() {
    }

    public static UUID weekdayAverage(JdbcTemplate jdbcTemplate, UUID companyId) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO forecast_model_configs (company_id, version, is_active, horizon_days, minimum_balance,
                                                            method_code, parameters, created_by)
                        VALUES (?, 1, true, 84, 0.00, 'HISTORICAL_BANK_CASH_FLOW_PATTERN',
                                '{"strategy": "WEEKDAY_AVERAGE", "lookbackWeeks": 8, "balanceStaleAfterDays": 3,
                                  "minimumSamplesPerWeekday": 4}'::jsonb, ?)
                        RETURNING id
                        """, UUID.class, companyId, UUID.randomUUID());
    }
}
