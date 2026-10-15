package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.PlatformRole;
import com.flowledger.identity.entity.User;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in user and the companies they can act for. Only active memberships in active
 * companies are listed; each service still checks access on every request.
 */
public record CurrentUserResponse(
        UUID userId,
        String email,
        String fullName,
        PlatformRole platformRole,
        List<Membership> memberships
) {

    public record Membership(UUID membershipId, UUID companyId, String companyName, CompanyRole companyRole) {

        static Membership from(CompanyMembership membership) {
            return new Membership(membership.getId(), membership.getCompany().getId(),
                    membership.getCompany().getName(), membership.getCompanyRole());
        }
    }

    public static CurrentUserResponse from(User user, List<CompanyMembership> memberships) {
        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPlatformRole(),
                memberships.stream().map(Membership::from).toList()
        );
    }
}
