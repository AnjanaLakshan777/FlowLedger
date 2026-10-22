package com.flowledger.forecast.schedule;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts the daily scheduled forecast at flowledger.daily-forecast.cron, in Asia/Colombo time (the
 * companies' time zone, as for every forecast date). Off with FORECAST_DAILY_RUN_ENABLED=false.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flowledger.daily-forecast.enabled", havingValue = "true")
public class DailyForecastScheduler {

    private final DailyForecastService dailyForecastService;

    @Scheduled(cron = "${flowledger.daily-forecast.cron}", zone = "Asia/Colombo")
    public void runDailyForecast() {
        dailyForecastService.runAll();
    }
}
