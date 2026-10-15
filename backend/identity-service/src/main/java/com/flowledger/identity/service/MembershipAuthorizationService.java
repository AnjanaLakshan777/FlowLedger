package com.flowledger.identity.service;

import com.flowledger.identity.dto.MembershipAuthorizationResponse;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MembershipAuthorizationService {

    private final CompanyMembershipRepository membershipRepository;
    private final CompanyRepository companyRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    // Statuses and roles are read at the time of the check, so locking a user, suspending a
    // membership, closing a company or removing a system admin takes effect immediately, even
    // for access tokens that are still valid.
    @Transactional(readOnly = true)
    public MembershipAuthorizationResponse checkMembership(UUID userId, UUID companyId) {
        CompanyRole companyRole = membershipRepository.findWithUserAndCompany(companyId, userId)
                .filter(CompanyMembership::grantsCompanyAccess)
                .map(CompanyMembership::getCompanyRole)
                .orElse(null);

        boolean platformAdmin = adminAuthorizationService.isActivePlatformAdmin(userId)
                && companyRepository.findById(companyId)
                .filter(company -> company.getStatus() == CompanyStatus.ACTIVE)
                .isPresent();

        if (companyRole == null && !platformAdmin) {
            return MembershipAuthorizationResponse.denied();
        }
        return new MembershipAuthorizationResponse(true, companyRole, platformAdmin);
    }
}
