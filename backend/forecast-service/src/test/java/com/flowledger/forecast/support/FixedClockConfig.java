package com.flowledger.forecast.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

// "Today" for forecast tests: 15 October 2026, 10:00 in Asia/Colombo.
@TestConfiguration
public class FixedClockConfig {

    public static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    public static final Instant NOW = Instant.parse("2026-10-15T04:30:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(NOW, COLOMBO);
    }
}
