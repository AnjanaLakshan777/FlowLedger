package com.flowledger.payment.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the publisher on a fixed delay while flowledger.outbox.enabled is true. */
@Component
@ConditionalOnProperty(name = "flowledger.outbox.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OutboxPublishingScheduler {

    private final OutboxPublisher publisher;
    private final OutboxProperties properties;

    @Scheduled(fixedDelayString = "${flowledger.outbox.poll-interval}")
    public void publish() {
        // keep going while full batches come back, then wait for the next poll
        OutboxPublisher.Result result;
        do {
            result = publisher.publishDue();
        } while (result.claimed() == properties.batchSize() && result.failed() == 0);
    }
}
