package com.flowledger.payment.outbox;

import java.util.Map;
import java.util.Optional;

/** The routing key each payment event is published with, on the exchange named by flowledger.outbox.exchange. */
public final class OutboxRoutes {

    private static final Map<String, String> ROUTING_KEYS = Map.of(
            "ChequeIssued", "cheque.issued",
            "ChequeUpdated", "cheque.updated",
            "ChequeCancelled", "cheque.cancelled");

    private OutboxRoutes() {
    }

    // Empty for an unknown event type: the event stays pending instead of going to a guessed route.
    public static Optional<String> routingKey(String eventType) {
        return Optional.ofNullable(ROUTING_KEYS.get(eventType));
    }
}
