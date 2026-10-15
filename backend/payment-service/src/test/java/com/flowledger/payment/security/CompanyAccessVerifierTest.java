package com.flowledger.payment.security;

import com.flowledger.payment.config.IdentityClientConfig;
import com.flowledger.payment.config.IdentityServiceProperties;
import com.flowledger.payment.exception.IdentityServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyAccessVerifierTest {

    private static final String SECRET = "s".repeat(32);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"too-short-secret"})
    void shouldRefuseToStartWithoutStrongInternalApiSecret(String secret) {
        IdentityServiceProperties properties = properties("http://localhost:8080", secret);

        assertThatThrownBy(() -> new CompanyAccessVerifier(new IdentityClientConfig().identityRestClient(properties), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_INTERNAL_API_SECRET");
    }

    @Test
    void shouldFailClosedWhenIdentityServiceIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        IdentityServiceProperties properties = properties("http://127.0.0.1:" + closedPort, SECRET);
        CompanyAccessVerifier verifier =
                new CompanyAccessVerifier(new IdentityClientConfig().identityRestClient(properties), properties);

        assertThatThrownBy(() -> verifier.verify(user(), UUID.randomUUID(), EnumSet.allOf(CompanyRole.class)))
                .isInstanceOf(IdentityServiceUnavailableException.class);
    }

    private static IdentityServiceProperties properties(String baseUrl, String secret) {
        return new IdentityServiceProperties(baseUrl, secret, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static Jwt user() {
        return Jwt.withTokenValue("user-access-token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .build();
    }
}
