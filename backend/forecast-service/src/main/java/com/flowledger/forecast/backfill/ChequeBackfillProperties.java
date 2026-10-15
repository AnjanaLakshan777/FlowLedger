package com.flowledger.forecast.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cheque backfill settings.
 *
 * @param runOnStartup whether the service starts or resumes the backfill in the background
 * @param pageSize     cheques per scan page, 1 to 500 (payment-service's maximum)
 * @param maxAttempts  tries for one page before this pass stops; the run stays resumable
 */
@ConfigurationProperties("flowledger.cheque-backfill")
public record ChequeBackfillProperties(
        boolean runOnStartup,
        int pageSize,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff
) {

    public ChequeBackfillProperties {
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("flowledger.cheque-backfill.page-size must be between 1 and 500");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("flowledger.cheque-backfill.max-attempts must be at least 1");
        }
    }
}
