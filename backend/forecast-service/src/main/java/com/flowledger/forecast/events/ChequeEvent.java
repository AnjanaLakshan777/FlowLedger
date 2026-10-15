package com.flowledger.forecast.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A validated cheque event from payment-service (schema version 1).
 *
 * @param messageId            the outbox event ID, used to recognise redeliveries
 * @param version              the cheque's version after the change
 * @param sourceEventCreatedAt when payment-service wrote the outbox event (AMQP timestamp), not the
 *                             cheque's business update time; null if the message has none
 */
public record ChequeEvent(
        UUID messageId,
        String eventType,
        UUID companyId,
        UUID chequeId,
        UUID bankAccountId,
        String chequeNumber,
        String category,
        BigDecimal amount,
        LocalDate issueDate,
        LocalDate expectedClearingDate,
        String status,
        int version,
        Instant sourceEventCreatedAt
) {
}
