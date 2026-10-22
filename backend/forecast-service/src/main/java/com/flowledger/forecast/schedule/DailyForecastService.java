package com.flowledger.forecast.schedule;

import com.flowledger.forecast.service.ForecastService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Makes the daily scheduled forecast, so every company in use has a forecast for the day even if
 * nobody opens the forecast screens. A company is in use when its users made a forecast in the last
 * {@code activeWithinDays} days; that keeps the job to companies that use forecasting, without a
 * company list from identity-service. Each company is independent: one that cannot be forecast
 * today (no confirmed balance, cheque data not verified, a service down) is logged and skipped.
 */
@Slf4j
@Service
public class DailyForecastService {

    public record Outcome(int companies, int created, int unchanged, int failed) {
    }

    private final ForecastService forecastService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final int activeWithinDays;

    public DailyForecastService(ForecastService forecastService, JdbcTemplate jdbcTemplate, Clock clock,
                                @Value("${flowledger.daily-forecast.active-within-days:30}") int activeWithinDays) {
        this.forecastService = forecastService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.activeWithinDays = activeWithinDays;
    }

    public Outcome runAll() {
        return run(companiesInUse());
    }

    Outcome run(List<UUID> companies) {
        int created = 0;
        int unchanged = 0;
        int failed = 0;
        for (UUID companyId : companies) {
            try {
                if (forecastService.createScheduledRun(companyId).created()) {
                    created++;
                } else {
                    unchanged++;
                }
            } catch (ErrorResponseException e) {
                // Expected answers such as no confirmed balance (409) or unverified cheque data (503).
                failed++;
                log.warn("Daily forecast for company {} not made: {} {}", companyId, e.getStatusCode().value(),
                        e.getBody().getDetail());
            } catch (RuntimeException e) {
                failed++;
                log.error("Daily forecast for company {} failed", companyId, e);
            }
        }
        Outcome outcome = new Outcome(companies.size(), created, unchanged, failed);
        log.info("Daily forecast: {} companies, {} new runs, {} unchanged, {} not made", outcome.companies(),
                outcome.created(), outcome.unchanged(), outcome.failed());
        return outcome;
    }

    // Companies whose users made a forecast within activeWithinDays, by when each run was made (as_of_at).
    List<UUID> companiesInUse() {
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(activeWithinDays)));
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT company_id FROM forecast_runs
                WHERE trigger_type = 'MANUAL' AND as_of_at >= ?
                ORDER BY company_id
                """, UUID.class, since);
    }
}
