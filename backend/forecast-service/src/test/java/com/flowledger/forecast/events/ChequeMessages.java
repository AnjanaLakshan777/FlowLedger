package com.flowledger.forecast.events;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds cheque messages shaped like payment-service's outbox publisher sends them: properties,
 * headers and a payload with the fields of schema version 1. Tests change single fields to make
 * them invalid.
 */
final class ChequeMessages {

    static final Date CREATED_AT = Date.from(java.time.Instant.parse("2026-10-15T08:30:00Z"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final UUID messageId;
    // The messageId property as sent; tests replace it with a missing or malformed value.
    String messageIdProperty;
    final Map<String, Object> headers = new LinkedHashMap<>();
    final Map<String, Object> payload = new LinkedHashMap<>();
    String type;
    String contentType = MessageProperties.CONTENT_TYPE_JSON;
    Date timestamp = CREATED_AT;
    byte[] rawBody;

    private ChequeMessages(UUID messageId, String eventType, UUID companyId, UUID chequeId, int version) {
        this.messageId = messageId;
        this.messageIdProperty = messageId.toString();
        this.type = eventType;
        headers.put("eventType", eventType);
        headers.put("schemaVersion", 1);
        headers.put("aggregateType", "Cheque");
        headers.put("aggregateId", chequeId.toString());
        headers.put("companyId", companyId.toString());
        payload.put("chequeId", chequeId.toString());
        payload.put("companyId", companyId.toString());
        payload.put("bankAccountId", "5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f");
        payload.put("chequeNumber", "000123");
        payload.put("category", "SUPPLIER_PAYMENT");
        payload.put("amount", "1500.00");
        payload.put("issueDate", "2026-10-15");
        payload.put("expectedClearingDate", "2026-10-22");
        payload.put("status", "ChequeCancelled".equals(eventType) ? "CANCELLED" : "ISSUED");
        payload.put("version", version);
    }

    static ChequeMessages event(String eventType, UUID companyId, UUID chequeId, int version) {
        return new ChequeMessages(UUID.randomUUID(), eventType, companyId, chequeId, version);
    }

    ChequeMessages header(String name, Object value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.put(name, value);
        }
        return this;
    }

    ChequeMessages field(String name, Object value) {
        if (value == null) {
            payload.remove(name);
        } else {
            payload.put(name, value);
        }
        return this;
    }

    ChequeMessages body(String body) {
        this.rawBody = body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    String routingKey() {
        return switch (String.valueOf(headers.get("eventType"))) {
            case "ChequeIssued" -> "cheque.issued";
            case "ChequeCancelled" -> "cheque.cancelled";
            default -> "cheque.updated";
        };
    }

    Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageIdProperty);
        properties.setType(type);
        properties.setContentType(contentType);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setTimestamp(timestamp);
        headers.forEach(properties::setHeader);
        byte[] body = rawBody != null ? rawBody : JSON.writeValueAsBytes(payload);
        return new Message(body, properties);
    }
}
