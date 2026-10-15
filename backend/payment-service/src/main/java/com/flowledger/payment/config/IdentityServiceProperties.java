package com.flowledger.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("flowledger.identity")
public record IdentityServiceProperties(
        String baseUrl,
        String internalApiSecret,
        Duration connectTimeout,
        Duration readTimeout
) {

    // Records print every component; keep the internal API secret out of logs.
    @Override
    public String toString() {
        return "IdentityServiceProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
