package com.flowledger.forecast.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cheque projection verification settings.
 *
 * @param maxAttempts checks per company in one verification pass while the result is
 *                    RETRY_REQUIRED (for example while events are still arriving)
 * @param retryDelay  wait between those checks
 */
@ConfigurationProperties("flowledger.cheque-verification")
public record ChequeVerificationProperties(int maxAttempts, Duration retryDelay) {

    public ChequeVerificationProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("flowledger.cheque-verification.max-attempts must be at least 1");
        }
    }
}
