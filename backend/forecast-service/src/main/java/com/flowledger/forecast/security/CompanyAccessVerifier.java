package com.flowledger.forecast.security;

import com.flowledger.forecast.config.IdentityServiceProperties;
import com.flowledger.forecast.exception.CompanyAccessDeniedException;
import com.flowledger.forecast.exception.IdentityServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;
import java.util.UUID;

/**
 * Asks identity-service whether the user may act for a company, then checks the returned role
 * against the roles the operation permits. Fails closed: if identity-service cannot answer,
 * the request is refused, never allowed.
 */
@Slf4j
@Component
public class CompanyAccessVerifier {

    static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String MEMBERSHIP_PATH = "/internal/v1/identity/companies/{companyId}/membership";

    private final RestClient identityRestClient;
    private final String internalApiSecret;

    public CompanyAccessVerifier(@Qualifier("identityRestClient") RestClient identityRestClient,
                                 IdentityServiceProperties properties) {
        String secret = properties.internalApiSecret();
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "IDENTITY_INTERNAL_API_SECRET must be set to at least " + MIN_SECRET_LENGTH + " characters.");
        }
        this.identityRestClient = identityRestClient;
        this.internalApiSecret = secret;
    }

    public void verify(Jwt user, UUID companyId, Set<CompanyRole> permittedRoles) {
        MembershipAuthorization authorization = fetchMembership(user, companyId);

        if (!authorization.allowed() || !permittedRoles.contains(authorization.companyRole())) {
            throw new CompanyAccessDeniedException();
        }
    }

    // The user's own token is forwarded so identity-service identifies the user from a token it
    // verifies itself, rather than from an ID this service passes along.
    private MembershipAuthorization fetchMembership(Jwt user, UUID companyId) {
        MembershipAuthorization authorization;
        try {
            authorization = identityRestClient.get()
                    .uri(MEMBERSHIP_PATH, companyId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.getTokenValue())
                    .header(INTERNAL_API_KEY_HEADER, internalApiSecret)
                    .retrieve()
                    .body(MembershipAuthorization.class);
        } catch (RestClientException e) {
            log.warn("Company membership check against identity-service failed: {}", e.getMessage());
            throw new IdentityServiceUnavailableException();
        }

        if (authorization == null) {
            log.warn("Company membership check against identity-service returned no body");
            throw new IdentityServiceUnavailableException();
        }
        return authorization;
    }
}
