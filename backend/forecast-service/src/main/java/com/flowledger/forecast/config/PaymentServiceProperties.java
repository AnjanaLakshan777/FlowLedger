package com.flowledger.forecast.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param internalApiSecret key for payment-service's internal API (PAYMENT_INTERNAL_API_SECRET),
 *                          used only by the cheque backfill; may be empty while the backfill is not run
 */
@ConfigurationProperties("flowledger.payment")
public record PaymentServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                       String internalApiSecret) {
}
