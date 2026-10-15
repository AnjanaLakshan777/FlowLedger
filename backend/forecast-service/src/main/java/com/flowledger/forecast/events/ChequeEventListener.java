package com.flowledger.forecast.events;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Consumes payment-service cheque events with manual acknowledgement:
 * <ul>
 *     <li>applied, stale and duplicate events are acknowledged after the transaction commits;</li>
 *     <li>invalid events are rejected without requeue, so the broker moves them to the dead-letter
 *     queue;</li>
 *     <li>other failures, such as the database being unavailable, are retried here a few times with
 *     backoff, each attempt in a new transaction, and then rejected the same way.</li>
 * </ul>
 * Only the message ID, event type and cheque ID are logged, never payload values.
 */
@Slf4j
@Component
public class ChequeEventListener {

    private final ChequeEventParser parser;
    private final ChequeEventProcessor processor;
    private final ChequeEventProperties properties;
    private final Sleeper sleeper;

    /** Waits between attempts; replaceable so tests do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    @Autowired
    public ChequeEventListener(ChequeEventParser parser, ChequeEventProcessor processor,
                               ChequeEventProperties properties) {
        this(parser, processor, properties, duration -> Thread.sleep(duration));
    }

    ChequeEventListener(ChequeEventParser parser, ChequeEventProcessor processor,
                        ChequeEventProperties properties, Sleeper sleeper) {
        this.parser = parser;
        this.processor = processor;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    @RabbitListener(id = "chequeEvents", queues = "${flowledger.cheque-events.queue}", ackMode = "MANUAL",
            autoStartup = "${flowledger.cheque-events.enabled}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = loggable(message.getMessageProperties().getMessageId());

        ChequeEvent event;
        try {
            event = parser.parse(message);
        } catch (RuntimeException e) {
            log.warn("Rejected cheque message {}: {}", messageId, reason(e));
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        ChequeEventProcessor.Outcome outcome;
        try {
            outcome = processWithRetry(event);
        } catch (InterruptedException e) {
            // Shutting down: give the message back to the broker for another delivery.
            Thread.currentThread().interrupt();
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        if (outcome == null) {
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        // Only after the commit. If this acknowledgement is lost, the broker delivers the event
        // again and it is acknowledged as a duplicate.
        channel.basicAck(deliveryTag, false);
        log.info("Cheque event {} ({}) for cheque {}: {}", event.messageId(), event.eventType(), event.chequeId(),
                outcome);
    }

    // Returns null if the event must go to the dead-letter queue.
    private ChequeEventProcessor.Outcome processWithRetry(ChequeEvent event) throws InterruptedException {
        Duration backoff = properties.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            try {
                return processor.process(event);
            } catch (InvalidChequeEventException | DataIntegrityViolationException e) {
                log.warn("Rejected cheque event {} ({}) for cheque {}: {}", event.messageId(), event.eventType(),
                        event.chequeId(), reason(e));
                return null;
            } catch (RuntimeException e) {
                if (attempt >= properties.maxAttempts()) {
                    log.error("Cheque event {} ({}) for cheque {} failed after {} attempts, moving it to the "
                            + "dead-letter queue: {}", event.messageId(), event.eventType(), event.chequeId(),
                            attempt, reason(e));
                    return null;
                }
                log.warn("Cheque event {} ({}) attempt {} failed, retrying in {} ms: {}", event.messageId(),
                        event.eventType(), attempt, backoff.toMillis(), reason(e));
            }
            sleeper.sleep(backoff);
            backoff = min(backoff.multipliedBy(2), properties.maxBackoff());
        }
    }

    // Exception class only: database and parser messages can quote parameter values.
    private static String reason(RuntimeException e) {
        return e instanceof InvalidChequeEventException ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String loggable(String messageId) {
        if (messageId == null) {
            return "(no message ID)";
        }
        return messageId.length() <= 64 ? messageId.replaceAll("[^A-Za-z0-9-]", "?") : "(oversized message ID)";
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
