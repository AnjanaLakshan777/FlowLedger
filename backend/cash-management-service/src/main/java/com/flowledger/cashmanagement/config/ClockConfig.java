package com.flowledger.cashmanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    // MVP companies operate in Sri Lanka (identity-service's default company time zone), so
    // "today" for sale-date checks is the date in Asia/Colombo, not the server's time zone.
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Colombo"));
    }
}
