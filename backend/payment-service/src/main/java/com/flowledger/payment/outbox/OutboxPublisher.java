package com.flowledger.payment.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes due outbox events to RabbitMQ, at least once. An event is marked published only after
 * the broker confirms it (ack) and did not return it as unroutable. Anything else — broker down,
 * nack, no confirm in time, unroutable, unknown event type — keeps it pending with a retry time.
 * If the process stops after the ack but before the event is marked, the lease expires and the
 * event is sent again with the same message ID, so consumers must deduplicate on it.
 */
@Slf4j
@Component
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 300;

    private final OutboxStore store;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;
    private final RetryBackoff backoff;

    @Autowired
    public OutboxPublisher(OutboxStore store, RabbitTemplate rabbitTemplate, OutboxProperties properties) {
        this(store, rabbitTemplate, properties,
                new RetryBackoff(properties.initialBackoff(), properties.maxBackoff(), () -> ThreadLocalRandom.current().nextDouble()));
    }

    OutboxPublisher(OutboxStore store, RabbitTemplate rabbitTemplate, OutboxProperties properties, RetryBackoff backoff) {
        this.store = store;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.backoff = backoff;
    }

    public record Result(int published, int failed) {
        public int claimed() {
            return published + failed;
        }
    }

    /** Claims one batch of due events and publishes them. */
    public Result publishDue() {
        return publishDue(null);
    }

    // Restricted to one company's events; tests use it so they never publish other rows.
    Result publishDue(UUID companyId) {
        List<ClaimedEvent> events = store.claim(properties.batchSize(), properties.lease(), companyId);
        int published = 0;
        for (ClaimedEvent event : events) {
            Optional<String> failure = publish(event);
            if (failure.isEmpty()) {
                store.markPublished(event.id());
                published++;
            } else {
                store.scheduleRetry(event.id(), backoff.delayFor(event.attempt()), failure.get());
                log.warn("Outbox event {} ({}) not published, attempt {}: {}",
                        event.id(), event.eventType(), event.attempt(), failure.get());
            }
        }
        return new Result(published, events.size() - published);
    }

    // Empty when the broker confirmed a routable message; otherwise why it was not published.
    private Optional<String> publish(ClaimedEvent event) {
        Optional<String> routingKey = OutboxRoutes.routingKey(event.eventType());
        if (routingKey.isEmpty()) {
            return Optional.of("No route for event type " + event.eventType());
        }

        CorrelationData correlation = new CorrelationData(event.id().toString());
        try {
            rabbitTemplate.send(OutboxRoutes.EXCHANGE, routingKey.get(), message(event), correlation);
        } catch (AmqpException e) {
            return Optional.of(sanitize("Broker unavailable", e));
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Optional.of("No publisher confirm within " + properties.confirmTimeout().toMillis() + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("Interrupted while waiting for the publisher confirm");
        } catch (ExecutionException e) {
            return Optional.of(sanitize("Publisher confirm failed", e.getCause()));
        }

        if (!confirm.ack()) {
            return Optional.of(truncate("Broker refused the message (nack)"
                    + (confirm.reason() == null ? "" : ": " + confirm.reason())));
        }
        // With mandatory publishing the broker returns a message no queue is bound for, before its ack.
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            return Optional.of(truncate("Unroutable: " + returned.getReplyCode() + " " + returned.getReplyText()
                    + " (" + returned.getRoutingKey() + ")"));
        }
        return Optional.empty();
    }

    private static Message message(ClaimedEvent event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.id().toString());
        props.setType(event.eventType());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setTimestamp(Date.from(event.createdAt()));
        props.setHeader("eventType", event.eventType());
        props.setHeader("schemaVersion", event.schemaVersion());
        props.setHeader("aggregateType", event.aggregateType());
        props.setHeader("aggregateId", event.aggregateId().toString());
        props.setHeader("companyId", event.companyId());
        // The payload is published as stored; it never contains payee names or notes.
        return MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8)).andProperties(props).build();
    }

    // Exception type and first line of the message only: no stack traces, no payload.
    private static String sanitize(String what, Throwable e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
        return truncate(what + ": " + (e == null ? "unknown" : e.getClass().getSimpleName()) + (message.isEmpty() ? "" : " - " + message));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
