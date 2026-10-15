package com.flowledger.payment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Outbox publishing settings.
 *
 * @param enabled        whether the scheduled publisher runs (it pauses sending only; it is not the
 *                       activation boundary, which is the publishable column)
 * @param lease          how long a claimed event is reserved for one publishing attempt; after it
 *                       expires the event can be claimed again, so a crashed attempt never strands it
 * @param confirmTimeout how long to wait for the broker's publisher confirm
 */
@ConfigurationProperties("flowledger.outbox")
public record OutboxProperties(
        boolean enabled,
        int batchSize,
        Duration pollInterval,
        Duration lease,
        Duration confirmTimeout,
        Duration initialBackoff,
        Duration maxBackoff
) {
}
