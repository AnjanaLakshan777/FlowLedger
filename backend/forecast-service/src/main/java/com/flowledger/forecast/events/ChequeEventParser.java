package com.flowledger.forecast.events;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates a payment-service cheque message (schema version 1) strictly and turns it into a
 * {@link ChequeEvent}. Unknown extra payload fields are ignored and never stored. Error messages
 * name the field, never its value.
 */
@Component
@RequiredArgsConstructor
public class ChequeEventParser {

    static final int SCHEMA_VERSION = 1;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> EVENT_TYPES = Set.of("ChequeIssued", "ChequeUpdated", "ChequeCancelled");
    private static final Set<String> CATEGORIES = Set.of("SUPPLIER_PAYMENT", "SALARY", "TAX", "OTHER_EXPENSES");
    private static final Pattern CHEQUE_NUMBER = Pattern.compile("[0-9]{1,40}");
    // Plain decimal as payment-service writes it, fitting NUMERIC(19, 2); no exponent or sign.
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,17}(\\.[0-9]{1,2})?");

    private final JsonMapper jsonMapper;

    public ChequeEvent parse(Message message) {
        MessageProperties props = message.getMessageProperties();
        UUID messageId = uuid(props.getMessageId(), "messageId");

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
        UUID aggregateId = uuid(header(props, "aggregateId"), "aggregateId header");
        UUID companyId = uuid(header(props, "companyId"), "companyId header");

        Map<String, Object> payload = payload(message.getBody());
        UUID chequeId = uuid(text(payload, "chequeId"), "chequeId");
        if (!chequeId.equals(aggregateId)) {
            throw new InvalidChequeEventException("chequeId does not match aggregateId");
        }
        if (!uuid(text(payload, "companyId"), "companyId").equals(companyId)) {
            throw new InvalidChequeEventException("companyId does not match the companyId header");
        }

        String chequeNumber = text(payload, "chequeNumber");
        if (!CHEQUE_NUMBER.matcher(chequeNumber).matches()) {
            throw new InvalidChequeEventException("Invalid chequeNumber");
        }
        String category = text(payload, "category");
        if (!CATEGORIES.contains(category)) {
            throw new InvalidChequeEventException("Invalid category");
        }
        BigDecimal amount = amount(text(payload, "amount"));
        LocalDate issueDate = date(text(payload, "issueDate"), "issueDate");
        LocalDate clearingDate = date(text(payload, "expectedClearingDate"), "expectedClearingDate");
        if (clearingDate.isBefore(issueDate)) {
            throw new InvalidChequeEventException("expectedClearingDate is before issueDate");
        }
        String status = text(payload, "status");
        String expectedStatus = "ChequeCancelled".equals(eventType) ? "CANCELLED" : "ISSUED";
        if (!expectedStatus.equals(status)) {
            throw new InvalidChequeEventException("status does not match eventType");
        }
        if (!(payload.get("version") instanceof Integer version) || version < 1) {
            throw new InvalidChequeEventException("Invalid version");
        }

        return new ChequeEvent(messageId, eventType, companyId, chequeId, uuid(text(payload, "bankAccountId"), "bankAccountId"),
                chequeNumber, category, amount, issueDate, clearingDate, status, version,
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

    private static String text(Map<String, Object> payload, String field) {
        if (payload.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new InvalidChequeEventException("Missing or invalid " + field);
    }

    private static UUID uuid(String value, String field) {
        try {
            if (value != null) {
                return UUID.fromString(value);
            }
        } catch (IllegalArgumentException e) {
            // reported below
        }
        throw new InvalidChequeEventException("Missing or invalid " + field);
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidChequeEventException("Invalid " + field);
        }
    }

    private static BigDecimal amount(String value) {
        if (AMOUNT.matcher(value).matches()) {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() > 0) {
                return amount;
            }
        }
        throw new InvalidChequeEventException("Invalid amount");
    }
}
