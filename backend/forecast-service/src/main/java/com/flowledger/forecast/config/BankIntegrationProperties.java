package com.flowledger.forecast.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param internalApiSecret key for bank-integration-service's internal API
 *                          (BANK_INTEGRATION_INTERNAL_API_SECRET), used only by the daily scheduled forecast
 */
@ConfigurationProperties("flowledger.bank-integration")
public record BankIntegrationProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                        String internalApiSecret) {
}
