package com.flowledger.bankintegration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Lets only FlowLedger services call bank-integration-service's internal API. Callers send the shared
 * BANK_INTEGRATION_INTERNAL_API_SECRET in {@value #HEADER_NAME}; no user token is involved, because the
 * caller is a service job, not a person. Without a configured secret every request is refused.
 * <p>
 * Not a Spring bean on purpose: as a bean it would also be registered as a servlet filter
 * for every request, not just for the internal security chain.
 */
class InternalApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Internal-Api-Key";
    static final String AUTHORITY = "INTERNAL_SERVICE";

    private static final String UNAUTHORIZED_BODY =
            "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Invalid service credentials.\"}";

    // Null when no secret is configured: nothing matches then.
    private final byte[] expectedSecretDigest;

    InternalApiKeyFilter(String secret) {
        this.expectedSecretDigest = secret == null || secret.isEmpty() ? null : sha256(secret);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String providedSecret = request.getHeader(HEADER_NAME);

        // Comparing fixed-length digests keeps the check constant-time regardless of input length.
        if (expectedSecretDigest == null || providedSecret == null
                || !MessageDigest.isEqual(sha256(providedSecret), expectedSecretDigest)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "internal-service", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
