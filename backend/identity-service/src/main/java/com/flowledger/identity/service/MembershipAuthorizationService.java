package com.flowledger.identity.service;

import com.flowledger.identity.dto.MembershipAuthorizationResponse;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.entity.MembershipStatus;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MembershipAuthorizationService {

    private final CompanyMembershipRepository membershipRepository;

    // Statuses are read at the time of the check, so locking a user, suspending a membership or
    // closing a company takes effect immediately, even for access tokens that are still valid.
    @Transactional(readOnly = true)
    public MembershipAuthorizationResponse checkMembership(UUID userId, UUID companyId) {
        return membershipRepository.findWithUserAndCompany(companyId, userId)
                .filter(MembershipAuthorizationService::grantsAccess)
                .map(membership -> MembershipAuthorizationResponse.allowed(membership.getCompanyRole()))
                .orElseGet(MembershipAuthorizationResponse::denied);
    }

    private static boolean grantsAccess(CompanyMembership membership) {
        return membership.getStatus() == MembershipStatus.ACTIVE
                && membership.getUser().getAccountStatus() == AccountStatus.ACTIVE
                && membership.getCompany().getStatus() == CompanyStatus.ACTIVE;
    }
}
