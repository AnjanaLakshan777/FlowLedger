package com.flowledger.cashmanagement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Validates access tokens issued by identity-service. This service only verifies tokens;
 * it has no encoder and must never issue its own.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    // HS256 needs a key at least as long as its 256-bit hash output.
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties.secret()))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(tokenValidator(properties.issuer()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer) {
        // Spring accepts a token without exp by default; ours must always expire.
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setAllowEmptyExpiryClaim(false);

        return new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, JwtConfig::isUuid),
                new JwtClaimValidator<>(JwtClaimNames.IAT, Objects::nonNull));
    }

    static SecretKey signingKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. Provide a Base64-encoded key of at least 256 bits.");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET must be Base64-encoded.");
        }

        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 256 bits (32 bytes).");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    // identity-service puts the user ID in sub; anything else is not one of its tokens.
    private static boolean isUuid(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            UUID.fromString(subject);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
