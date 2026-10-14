package com.flowledger.bankintegration.security;

import com.flowledger.bankintegration.exception.CompanyAccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CompanyAccessVerifier {

    /**
     * Throws unless the user is an active member of the company.
     * <p>
     * identity-service does not yet expose company memberships to other services, and its
     * access tokens carry only the user ID. Until that integration exists, no user can be
     * shown to belong to a company, so every company-scoped request is refused.
     */
    public void verify(UUID userId, UUID companyId) {
        throw new CompanyAccessDeniedException();
    }
}
