package com.flowledger.identity.dto;

import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;

import java.util.UUID;

public record MembershipResponse(
        UUID membershipId,
        UUID companyId,
        UUID userId,
        String email,
        String fullName,
        CompanyRole companyRole,
        MembershipStatus status,
        AccountStatus accountStatus
) {

    public static MembershipResponse from(CompanyMembership membership) {
        return new MembershipResponse(
                membership.getId(),
                membership.getCompany().getId(),
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getFullName(),
                membership.getCompanyRole(),
                membership.getStatus(),
                membership.getUser().getAccountStatus()
        );
    }
}
