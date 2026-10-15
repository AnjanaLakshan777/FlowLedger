package com.flowledger.forecast.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    // MVP companies operate in Sri Lanka (identity-service's default company time zone), so
    // forecast dates, the balance date and "today" are dates in Asia/Colombo, not the server's time zone.
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Colombo"));
    }
}
