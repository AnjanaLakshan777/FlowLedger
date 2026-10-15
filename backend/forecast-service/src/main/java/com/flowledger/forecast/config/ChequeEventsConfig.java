package com.flowledger.forecast.config;

import com.flowledger.forecast.events.ChequeEventProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for payment-service cheque events, declared when the first connection opens.
 * The exchange is declared with the same settings as payment-service declares it. The queue is a
 * durable quorum queue whose rejected messages, and messages delivered more than the delivery
 * limit (for example when the service keeps crashing on one), go to the dead-letter queue.
 */
@Configuration
@EnableConfigurationProperties(ChequeEventProperties.class)
public class ChequeEventsConfig {

    @Bean
    public TopicExchange paymentEventsExchange(ChequeEventProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    public DirectExchange chequeEventsDeadLetterExchange(ChequeEventProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    public Queue chequeEventsQueue(ChequeEventProperties properties) {
        return QueueBuilder.durable(properties.queue())
                .quorum()
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .deliveryLimit(properties.deliveryLimit())
                .build();
    }

    @Bean
    public Queue chequeEventsDeadLetterQueue(ChequeEventProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).quorum().build();
    }

    @Bean
    public Binding chequeEventsBinding(Queue chequeEventsQueue, TopicExchange paymentEventsExchange,
                                       ChequeEventProperties properties) {
        return BindingBuilder.bind(chequeEventsQueue).to(paymentEventsExchange).with(properties.bindingKey());
    }

    @Bean
    public Binding chequeEventsDeadLetterBinding(Queue chequeEventsDeadLetterQueue,
                                                 DirectExchange chequeEventsDeadLetterExchange,
                                                 ChequeEventProperties properties) {
        return BindingBuilder.bind(chequeEventsDeadLetterQueue).to(chequeEventsDeadLetterExchange)
                .with(properties.deadLetterQueue());
    }
}
