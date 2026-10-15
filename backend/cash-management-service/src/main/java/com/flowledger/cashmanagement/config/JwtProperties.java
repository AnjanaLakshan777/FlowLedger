package com.flowledger.cashmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flowledger.security.jwt")
public record JwtProperties(String issuer, String secret) {

    // Records print every component; keep the signing secret out of logs.
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + "]";
    }
}
