package com.flowledger.forecast.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cheque-event consumer settings.
 *
 * @param enabled       whether the listener starts with the service
 * @param maxAttempts   tries for one delivery when processing fails for a transient reason, before the
 *                      message goes to the dead-letter queue
 * @param deliveryLimit broker-side redelivery limit of the quorum queue, a safety net for crash loops
 */
@ConfigurationProperties("flowledger.cheque-events")
public record ChequeEventProperties(
        boolean enabled,
        String exchange,
        String bindingKey,
        String queue,
        String deadLetterExchange,
        String deadLetterQueue,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        int deliveryLimit
) {
}
