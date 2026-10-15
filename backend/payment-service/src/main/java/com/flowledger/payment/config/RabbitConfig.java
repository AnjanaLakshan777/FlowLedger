package com.flowledger.payment.config;

import com.flowledger.payment.outbox.OutboxProperties;
import com.flowledger.payment.outbox.OutboxRoutes;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The durable topic exchange payment events are published to. Consumers declare and bind their
 * own queues; until one is bound, events are unroutable and stay pending in the outbox.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class RabbitConfig {

    @Bean
    public TopicExchange paymentExchange() {
        return new TopicExchange(OutboxRoutes.EXCHANGE, true, false);
    }
}
