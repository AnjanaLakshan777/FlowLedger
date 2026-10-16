package com.flowledger.forecast.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("flowledger.cash-management")
public record CashManagementProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
