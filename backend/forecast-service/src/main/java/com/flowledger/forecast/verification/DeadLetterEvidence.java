package com.flowledger.forecast.verification;

import com.flowledger.forecast.events.ChequeEventProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;
import java.util.Properties;

/**
 * How many cheque events sit in the dead-letter queue. Dead-lettered messages are not recorded in
 * the database, and a message can reach the queue without passing the listener (delivery limit),
 * so the queue itself is the evidence. Its messages are never read here.
 */
@Component
public class DeadLetterEvidence {

    private final AmqpAdmin amqpAdmin;
    private final String deadLetterQueue;

    public DeadLetterEvidence(AmqpAdmin amqpAdmin, ChequeEventProperties properties) {
        this.amqpAdmin = amqpAdmin;
        this.deadLetterQueue = properties.deadLetterQueue();
    }

    /**
     * Ready messages in the dead-letter queue, or empty if that cannot be known: the broker is
     * unreachable or the queue has not been declared. Messages being consumed by someone else are
     * not counted.
     */
    public OptionalLong deadLetterCount() {
        try {
            Properties queue = amqpAdmin.getQueueProperties(deadLetterQueue);
            if (queue != null && queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT) instanceof Number count) {
                return OptionalLong.of(count.longValue());
            }
        } catch (AmqpException e) {
            // reported as unavailable
        }
        return OptionalLong.empty();
    }
}
