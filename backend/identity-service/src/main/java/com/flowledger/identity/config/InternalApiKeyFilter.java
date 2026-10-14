package com.flowledger.identity.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Lets only FlowLedger services call the internal API. Callers send the shared
 * IDENTITY_INTERNAL_API_SECRET in {@value #HEADER_NAME}; the end user is still identified by
 * their own access token, which the resource server validates after this filter.
 * <p>
 * Not a Spring bean on purpose: as a bean it would also be registered as a servlet filter
 * for every request, not just for the internal security chain.
 */
class InternalApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Internal-Api-Key";

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String UNAUTHORIZED_BODY =
            "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Invalid service credentials.\"}";

    private final byte[] expectedSecretDigest;

    InternalApiKeyFilter(String secret) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "IDENTITY_INTERNAL_API_SECRET must be set to at least " + MIN_SECRET_LENGTH + " characters.");
        }
        this.expectedSecretDigest = sha256(secret);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String providedSecret = request.getHeader(HEADER_NAME);

        // Comparing fixed-length digests keeps the check constant-time regardless of input length.
        if (providedSecret == null || !MessageDigest.isEqual(sha256(providedSecret), expectedSecretDigest)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
