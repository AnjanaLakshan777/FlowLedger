package com.flowledger.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("flowledger.bank-integration")
public record BankIntegrationProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
