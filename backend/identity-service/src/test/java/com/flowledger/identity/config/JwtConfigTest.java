package com.flowledger.identity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigTest {

    private static final String ISSUER = "flowledger-identity";

    private final JwtConfig jwtConfig = new JwtConfig();
    private final SecretKey signingKey = JwtConfig.signingKey(randomSecret(32));
    private final JwtEncoder encoder = jwtConfig.jwtEncoder(signingKey);
    private final JwtDecoder decoder = jwtConfig.jwtDecoder(
            signingKey, new JwtProperties(ISSUER, Duration.ofMinutes(15), null));

    @Test
    void shouldDecodeTokenSignedWithConfiguredKey() {
        Jwt jwt = decoder.decode(encode(encoder, validClaims("user-1")));

        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(ISSUER);
    }

    @Test
    void shouldRejectTokenSignedWithDifferentKey() {
        JwtEncoder otherEncoder = jwtConfig.jwtEncoder(JwtConfig.signingKey(randomSecret(32)));
        String token = encode(otherEncoder, validClaims("user-1"));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void shouldRejectTokenWithTamperedPayload() {
        String[] genuine = encode(encoder, validClaims("user-1")).split("\\.");
        String[] forged = encode(encoder, validClaims("user-2")).split("\\.");
        String tampered = genuine[0] + "." + forged[1] + "." + genuine[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(1));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("user-1")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .build();

        String token = encode(encoder, claims);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void shouldRejectTokenFromAnotherIssuer() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("someone-else")
                .subject("user-1")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .build();

        String token = encode(encoder, claims);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void shouldRejectTokenWithoutExpiry() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("user-1")
                .issuedAt(Instant.now())
                .build();

        String token = encode(encoder, claims);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

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
        assertThatThrownBy(() -> JwtConfig.signingKey(randomSecret(31)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits");
    }

    private static JwtClaimsSet validClaims(String subject) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .build();
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String randomSecret(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
