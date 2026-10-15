package com.flowledger.payment.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * An outbox event reserved for one publishing attempt.
 *
 * @param payload the stored JSON payload, sent unchanged
 * @param attempt the number of this attempt, starting at 1
 */
public record ClaimedEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        String payload,
        String companyId,
        int attempt,
        Instant createdAt
) {
}
