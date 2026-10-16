package com.flowledger.identity.service;

import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.exception.OperationNotPermittedException;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Authorizes administrative operations in identity-service. Roles are read from the database on
 * every call, never from the access token, so revoking a role takes effect immediately.
 */
@Service
@RequiredArgsConstructor
public class AdminAuthorizationService {

    private final UserRepository userRepository;
    private final CompanyMembershipRepository membershipRepository;

    // Platform-wide operations such as creating companies.
    public void requirePlatformAdmin(UUID userId) {
        if (!isActivePlatformAdmin(userId)) {
            throw new OperationNotPermittedException("This operation requires a system admin.");
        }
    }

    // Company administration: a system admin for any company, or an active company admin for
    // their own company only.
    public void requireCompanyAdministration(UUID userId, UUID companyId) {
        if (isActivePlatformAdmin(userId)) {
            return;
        }
        boolean companyAdmin = membershipRepository.findWithUserAndCompany(companyId, userId)
                .filter(CompanyMembership::grantsCompanyAccess)
                .filter(CompanyMembership::isActiveCompanyAdmin)
                .isPresent();
        if (!companyAdmin) {
            throw new OperationNotPermittedException("You are not an administrator of this company.");
        }
    }

    // Reading company details: a system admin, or anyone who can act through the company.
    public void requireCompanyAccess(UUID userId, UUID companyId) {
        if (isActivePlatformAdmin(userId)) {
            return;
        }
        boolean member = membershipRepository.findWithUserAndCompany(companyId, userId)
                .filter(CompanyMembership::grantsCompanyAccess)
                .isPresent();
        if (!member) {
            throw new OperationNotPermittedException("You are not a member of this company.");
        }
    }

    public boolean isActivePlatformAdmin(UUID userId) {
        return userRepository.findById(userId)
                .filter(User::isActivePlatformAdmin)
                .isPresent();
    }
}
