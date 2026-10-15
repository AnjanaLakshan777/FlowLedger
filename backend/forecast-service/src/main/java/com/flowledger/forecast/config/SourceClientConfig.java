package com.flowledger.forecast.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Clients for the services that own the forecast inputs: bank-integration-service (account,
 * balance, statements, transactions) and payment-service (internal cheque scan and summary).
 */
@Configuration
@EnableConfigurationProperties({BankIntegrationProperties.class, PaymentServiceProperties.class})
public class SourceClientConfig {

    @Bean
    public RestClient bankIntegrationRestClient(BankIntegrationProperties properties) {
        return restClient(properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    @Bean
    public RestClient paymentRestClient(PaymentServiceProperties properties) {
        return restClient(properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());
    }

    private static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
