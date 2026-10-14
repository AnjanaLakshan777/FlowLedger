package com.flowledger.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("flowledger.security.jwt")
public record JwtProperties(String issuer, Duration accessTokenTtl, String secret) {

    // Records print every component; keep the signing secret out of logs.
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
