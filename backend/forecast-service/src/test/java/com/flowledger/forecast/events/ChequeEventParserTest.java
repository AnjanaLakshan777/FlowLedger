package com.flowledger.forecast.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChequeEventParserTest {

    private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CHEQUE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final ChequeEventParser parser = new ChequeEventParser(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(strings = {"ChequeIssued", "ChequeUpdated", "ChequeCancelled"})
    void shouldParseEachSupportedEventType(String eventType) {
        ChequeMessages message = ChequeMessages.event(eventType, COMPANY_ID, CHEQUE_ID, 3);

        ChequeEvent event = parser.parse(message.message());

        assertThat(event.messageId()).isEqualTo(message.messageId);
        assertThat(event.eventType()).isEqualTo(eventType);
        assertThat(event.companyId()).isEqualTo(COMPANY_ID);
        assertThat(event.chequeId()).isEqualTo(CHEQUE_ID);
        assertThat(event.chequeNumber()).isEqualTo("000123");
        assertThat(event.category()).isEqualTo("SUPPLIER_PAYMENT");
        assertThat(event.amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(event.issueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(event.expectedClearingDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(event.status()).isEqualTo(eventType.equals("ChequeCancelled") ? "CANCELLED" : "ISSUED");
        assertThat(event.version()).isEqualTo(3);
        assertThat(event.sourceEventCreatedAt()).isEqualTo(ChequeMessages.CREATED_AT.toInstant());
    }

    @Test
    void shouldIgnoreUnknownPayloadFields() {
        ChequeMessages message = ChequeMessages.event("ChequeIssued", COMPANY_ID, CHEQUE_ID, 1)
                .field("payeeName", "Some Supplier")
                .field("notes", "free text");

        assertThat(parser.parse(message.message()).chequeId()).isEqualTo(CHEQUE_ID);
    }

    @Test
    void shouldAcceptAMessageWithoutTimestamp() {
        ChequeMessages message = ChequeMessages.event("ChequeIssued", COMPANY_ID, CHEQUE_ID, 1);
        message.timestamp = null;

        assertThat(parser.parse(message.message()).sourceEventCreatedAt()).isNull();
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
                invalid("missing messageId", m -> { m.messageIdProperty = null; return m; }),
                invalid("messageId not a UUID", m -> { m.messageIdProperty = "outbox-1"; return m; }),
                invalid("schemaVersion 2", m -> m.header("schemaVersion", 2)),
                invalid("schemaVersion as text", m -> m.header("schemaVersion", "1")),
                invalid("missing schemaVersion", m -> m.header("schemaVersion", null)),
                invalid("unknown eventType", m -> m.header("eventType", "ChequeCleared")),
                invalid("missing eventType", m -> m.header("eventType", null)),
                invalid("type property differs", m -> { m.type = "ChequeCancelled"; return m; }),
                invalid("XML content type", m -> { m.contentType = "application/xml"; return m; }),
                invalid("malformed JSON", m -> m.body("{\"chequeId\": ")),
                invalid("JSON array", m -> m.body("[1, 2]")),
                invalid("empty body", m -> m.body("")),
                invalid("oversized body", m -> m.body("{\"x\":\"" + "a".repeat(17 * 1024) + "\"}")),
                invalid("aggregateId differs", m -> m.header("aggregateId", UUID.randomUUID().toString())),
                invalid("companyId differs from header", m -> m.field("companyId", UUID.randomUUID().toString())),
                invalid("companyId header missing", m -> m.header("companyId", null)),
                invalid("chequeId not a UUID", m -> m.field("chequeId", "cheque-1")),
                invalid("bankAccountId missing", m -> m.field("bankAccountId", null)),
                invalid("chequeNumber with letters", m -> m.field("chequeNumber", "12A")),
                invalid("chequeNumber as number", m -> m.field("chequeNumber", 123)),
                invalid("unknown category", m -> m.field("category", "RENT")),
                invalid("negative amount", m -> m.field("amount", "-1.00")),
                invalid("zero amount", m -> m.field("amount", "0.00")),
                invalid("three decimals", m -> m.field("amount", "10.005")),
                invalid("exponent amount", m -> m.field("amount", "1e3")),
                invalid("amount as number", m -> m.field("amount", 1500.00)),
                invalid("too large amount", m -> m.field("amount", "123456789012345678.00")),
                invalid("bad issueDate", m -> m.field("issueDate", "15/10/2026")),
                invalid("clearing before issue", m -> m.field("expectedClearingDate", "2026-10-01")),
                invalid("status differs from eventType", m -> m.field("status", "CANCELLED")),
                invalid("version zero", m -> m.field("version", 0)),
                invalid("version as text", m -> m.field("version", "2")),
                invalid("version missing", m -> m.field("version", null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    void shouldRejectInvalidMessages(String description, UnaryOperator<ChequeMessages> change) {
        ChequeMessages message = change.apply(ChequeMessages.event("ChequeIssued", COMPANY_ID, CHEQUE_ID, 1));

        assertThatThrownBy(() -> parser.parse(message.message())).isInstanceOf(InvalidChequeEventException.class);
    }

    @Test
    void shouldNotQuotePayloadValuesInErrors() {
        ChequeMessages message = ChequeMessages.event("ChequeIssued", COMPANY_ID, CHEQUE_ID, 1)
                .body("{\"payeeName\": \"Secret Payee\", \"amount\": ");

        assertThatThrownBy(() -> parser.parse(message.message()))
                .isInstanceOf(InvalidChequeEventException.class)
                .hasMessage("Payload is not a JSON object")
                .hasNoCause();
    }

    private static Arguments invalid(String description, UnaryOperator<ChequeMessages> change) {
        return Arguments.of(description, change);
    }
}
