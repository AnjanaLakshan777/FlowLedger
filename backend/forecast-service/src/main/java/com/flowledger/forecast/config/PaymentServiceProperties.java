package com.flowledger.forecast.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("flowledger.payment")
public record PaymentServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
