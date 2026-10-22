package com.flowledger.forecast.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param internalApiSecret key for cash-management-service's internal API
 *                          (CASH_MANAGEMENT_INTERNAL_API_SECRET), used only by the daily scheduled forecast
 */
@ConfigurationProperties("flowledger.cash-management")
public record CashManagementProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                       String internalApiSecret) {
}
