package com.flowledger.payment.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each broker answer and what it does to the outbox row, with the broker simulated. */
class OutboxPublisherTest {

    private static final OutboxProperties PROPERTIES = new OutboxProperties(
            true, 50, Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMinutes(15),
            "flowledger.payment");

    private final OutboxStore store = mock(OutboxStore.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(store, rabbit, PROPERTIES, new RetryBackoff(
            PROPERTIES.initialBackoff(), PROPERTIES.maxBackoff(), () -> 1.0));
    private ClaimedEvent event;

    @BeforeEach
    void setUp() {
        event = event("ChequeIssued", 1);
        when(store.claim(eq(50), eq(Duration.ofSeconds(60)), isNull())).thenReturn(List.of(event));
    }

    @Test
    void shouldMarkPublishedOnlyAfterAnAckForARoutableMessage() {
        brokerAnswers(new CorrelationData.Confirm(true, null), false);

        OutboxPublisher.Result result = publisher.publishDue();

        assertThat(result.published()).isEqualTo(1);
        verify(store).markPublished(event.id());
        verify(store, never()).scheduleRetry(any(), any(), anyString());

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("flowledger.payment"), eq("cheque.issued"), sent.capture(), any(CorrelationData.class));
        Message message = sent.getValue();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.id().toString());
        assertThat(message.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().<String>getHeader("eventType")).isEqualTo("ChequeIssued");
        assertThat(message.getMessageProperties().<Integer>getHeader("schemaVersion")).isEqualTo(1);
        assertThat(message.getMessageProperties().<String>getHeader("aggregateId")).isEqualTo(event.aggregateId().toString());
        assertThat(message.getMessageProperties().<String>getHeader("companyId")).isEqualTo(event.companyId());
        assertThat(new String(message.getBody())).isEqualTo(event.payload());
    }

    @Test
    void shouldKeepTheEventPendingOnANack() {
        brokerAnswers(new CorrelationData.Confirm(false, "simulated refusal"), false);

        publisher.publishDue();

        verify(store, never()).markPublished(any());
        verify(store).scheduleRetry(event.id(), Duration.ofSeconds(5), "Broker refused the message (nack): simulated refusal");
    }

    @Test
    void shouldKeepTheEventPendingWithoutAConfirmInTime() {
        // the broker never confirms
        OutboxPublisher.Result result = publisher.publishDue();

        assertThat(result.failed()).isEqualTo(1);
        verify(store, never()).markPublished(any());
        verify(store).scheduleRetry(event.id(), Duration.ofSeconds(5), "No publisher confirm within 200 ms");
    }

    @Test
    void shouldKeepAnUnroutableEventPendingEvenThoughTheBrokerAcked() {
        brokerAnswers(new CorrelationData.Confirm(true, null), true);

        publisher.publishDue();

        verify(store, never()).markPublished(any());
        verify(store).scheduleRetry(event.id(), Duration.ofSeconds(5), "Unroutable: 312 NO_ROUTE (cheque.issued)");
    }

    @Test
    void shouldKeepTheEventPendingWhenTheBrokerCannotBeReached() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishDue();

        verify(store, never()).markPublished(any());
        verify(store).scheduleRetry(event.id(), Duration.ofSeconds(5),
                "Broker unavailable: AmqpConnectException - java.net.ConnectException: Connection refused");
    }

    @Test
    void shouldNotGuessARouteForAnUnknownEventType() {
        ClaimedEvent unknown = event("ChequeDestroyed", 3);
        when(store.claim(eq(50), eq(Duration.ofSeconds(60)), isNull())).thenReturn(List.of(unknown));

        publisher.publishDue();

        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        // third attempt: 5s doubled twice, capped by the jitter at the top of the range
        verify(store).scheduleRetry(unknown.id(), Duration.ofSeconds(20), "No route for event type ChequeDestroyed");
    }

    @Test
    void shouldStoreOnlyAShortSingleLineError() {
        doThrow(new AmqpConnectException(new ConnectException("line one\nline two with details\n" + "x".repeat(500))))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishDue();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(store).scheduleRetry(eq(event.id()), any(), error.capture());
        assertThat(error.getValue()).doesNotContain("\n").doesNotContain("line two").hasSizeLessThanOrEqualTo(300);
    }

    private void brokerAnswers(CorrelationData.Confirm confirm, boolean returned) {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "flowledger.payment", "cheque.issued"));
            }
            correlation.getFuture().complete(confirm);
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static ClaimedEvent event(String type, int attempt) {
        UUID chequeId = UUID.randomUUID();
        String companyId = UUID.randomUUID().toString();
        return new ClaimedEvent(UUID.randomUUID(), "Cheque", chequeId, type, 1,
                "{\"chequeId\": \"" + chequeId + "\", \"companyId\": \"" + companyId + "\", \"amount\": \"1000.00\"}",
                companyId, attempt, Instant.parse("2026-10-15T10:00:00Z"));
    }
}
