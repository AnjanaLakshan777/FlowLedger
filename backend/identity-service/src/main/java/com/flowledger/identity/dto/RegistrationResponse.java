package com.flowledger.identity.dto;

import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;
import com.flowledger.identity.entity.User;

import java.time.Instant;
import java.util.UUID;

public record RegistrationResponse(
        UUID userId,
        String email,
        String fullName,
        AccountStatus accountStatus,
        UUID companyId,
        CompanyRole companyRole,
        MembershipStatus membershipStatus,
        Instant createdAt
) {

    public static RegistrationResponse from(User user, CompanyMembership membership) {
        return new RegistrationResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getAccountStatus(),
                membership.getCompany().getId(),
                membership.getCompanyRole(),
                membership.getStatus(),
                user.getCreatedAt()
        );
    }
}
