package com.flowledger.bankintegration.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

    @ParameterizedTest
    @NullAndEmptySource
    void shouldFailWhenSecretIsMissing(String secret) {
        assertThatThrownBy(() -> JwtConfig.signingKey(secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void shouldFailWhenSecretIsNotBase64() {
        assertThatThrownBy(() -> JwtConfig.signingKey("not base64 at all!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void shouldFailWhenSecretIsShorterThan256Bits() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> JwtConfig.signingKey(shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits");
    }

    @Test
    void shouldAcceptSecretOf256Bits() {
        String secret = Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(JwtConfig.signingKey(secret).getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void shouldNotPrintSecret() {
        JwtProperties properties = new JwtProperties("flowledger-identity", "c2VjcmV0LXZhbHVl");

        assertThat(properties.toString()).doesNotContain("c2VjcmV0LXZhbHVl");
    }
}
