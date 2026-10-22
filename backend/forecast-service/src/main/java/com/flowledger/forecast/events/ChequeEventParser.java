package com.flowledger.forecast.events;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a payment-service cheque message (schema version 1) strictly and turns it into a
 * {@link ChequeEvent}. The payload fields are checked by {@link ChequeFields}, the same rules the
 * backfill uses. Unknown extra payload fields are ignored and never stored. Error messages name
 * the field, never its value.
 */
@Component
@RequiredArgsConstructor
public class ChequeEventParser {

    static final int SCHEMA_VERSION = 1;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> EVENT_TYPES = Set.of(
            "ChequeIssued", "ChequeUpdated", "ChequeCancelled", "ChequeBounced");

    private final JsonMapper jsonMapper;

    public ChequeEvent parse(Message message) {
        MessageProperties props = message.getMessageProperties();
        UUID messageId = ChequeFields.uuid(props.getMessageId(), "messageId");

        Object schemaVersion = props.getHeader("schemaVersion");
        if (!(schemaVersion instanceof Number number) || number.intValue() != SCHEMA_VERSION
                || number.doubleValue() != SCHEMA_VERSION) {
            throw new InvalidChequeEventException("Unsupported schemaVersion");
        }
        String eventType = props.getHeader("eventType") instanceof String type ? type : null;
        if (eventType == null || !EVENT_TYPES.contains(eventType)) {
            throw new InvalidChequeEventException("Unknown eventType");
        }
        if (props.getType() != null && !props.getType().equals(eventType)) {
            throw new InvalidChequeEventException("Message type does not match eventType");
        }
        if (props.getContentType() != null && !props.getContentType().startsWith(MessageProperties.CONTENT_TYPE_JSON)) {
            throw new InvalidChequeEventException("Unsupported content type");
        }
        UUID aggregateId = ChequeFields.uuid(header(props, "aggregateId"), "aggregateId header");
        UUID companyId = ChequeFields.uuid(header(props, "companyId"), "companyId header");

        ChequeFields fields = ChequeFields.from(payload(message.getBody()));
        if (!fields.chequeId().equals(aggregateId)) {
            throw new InvalidChequeEventException("chequeId does not match aggregateId");
        }
        if (!fields.companyId().equals(companyId)) {
            throw new InvalidChequeEventException("companyId does not match the companyId header");
        }
        String expectedStatus = switch (eventType) {
            case "ChequeCancelled" -> "CANCELLED";
            case "ChequeBounced" -> "BOUNCED";
            default -> "ISSUED";
        };
        if (!expectedStatus.equals(fields.status())) {
            throw new InvalidChequeEventException("status does not match eventType");
        }

        return new ChequeEvent(messageId, eventType, companyId, fields.chequeId(), fields.bankAccountId(),
                fields.chequeNumber(), fields.category(), fields.amount(), fields.issueDate(),
                fields.expectedClearingDate(), fields.status(), fields.version(),
                props.getTimestamp() == null ? null : props.getTimestamp().toInstant());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new InvalidChequeEventException("Missing or oversized payload");
        }
        try {
            Object parsed = jsonMapper.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (JacksonException e) {
            // reported below without the parser's message, which can quote the payload
        }
        throw new InvalidChequeEventException("Payload is not a JSON object");
    }

    private static String header(MessageProperties props, String name) {
        return props.getHeader(name) instanceof String value ? value : null;
    }
}
