package com.flowledger.forecast.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

// "Today" for Forecast vs Actual tests: 15 November 2026, so October is the last ended month.
@TestConfiguration
public class NextMonthClockConfig {

    public static final Instant NOW = Instant.parse("2026-11-15T04:30:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(NOW, FixedClockConfig.COLOMBO);
    }
}
