package com.flowledger.payment.outbox;

import java.util.Map;
import java.util.Optional;

/** Where each payment event is published: the flowledger.payment topic exchange and a routing key per type. */
public final class OutboxRoutes {

    public static final String EXCHANGE = "flowledger.payment";

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
