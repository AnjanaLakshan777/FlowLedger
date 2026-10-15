package com.flowledger.identity.service;

import com.flowledger.identity.dto.MembershipResponse;
import com.flowledger.identity.dto.UpdateMembershipRequest;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;
import com.flowledger.identity.exception.CompanyNotFoundException;
import com.flowledger.identity.exception.InvalidMembershipUpdateException;
import com.flowledger.identity.exception.LastCompanyAdminException;
import com.flowledger.identity.exception.MembershipNotFoundException;
import com.flowledger.identity.exception.OperationNotPermittedException;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Company user administration: a company admin for their own company, a system admin for any
 * company. Only company roles can be assigned here; platform roles are never granted via the API.
 */
@Service
@RequiredArgsConstructor
public class MembershipManagementService {

    private final CompanyMembershipRepository membershipRepository;
    private final CompanyRepository companyRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    @Transactional(readOnly = true)
    public List<MembershipResponse> listMemberships(UUID actorId, UUID companyId) {
        adminAuthorizationService.requireCompanyAdministration(actorId, companyId);
        if (!companyRepository.existsById(companyId)) {
            throw new CompanyNotFoundException();
        }
        return membershipRepository.findAllInCompany(companyId).stream()
                .map(MembershipResponse::from)
                .toList();
    }

    @Transactional
    public MembershipResponse updateMembership(UUID actorId, UUID companyId, UUID membershipId,
                                               UpdateMembershipRequest request) {
        if (request.companyRole() == null && request.status() == null) {
            throw new InvalidMembershipUpdateException("Provide companyRole, status or both.");
        }
        if (request.status() == MembershipStatus.INVITED) {
            throw new InvalidMembershipUpdateException("status can only be changed to ACTIVE or SUSPENDED.");
        }

        // Lock the company's active admins before anything is checked, so two concurrent changes
        // cannot each see another admin and together remove the last one. The caller's own admin
        // rights are then read after the lock and cannot be stale.
        List<CompanyMembership> activeAdmins = membershipRepository.lockByCompanyAndRoleAndStatus(
                companyId, CompanyRole.COMPANY_ADMIN, MembershipStatus.ACTIVE);
        adminAuthorizationService.requireCompanyAdministration(actorId, companyId);

        CompanyMembership membership = membershipRepository.findInCompany(companyId, membershipId)
                .orElseThrow(MembershipNotFoundException::new);
        if (membership.getUser().getId().equals(actorId)) {
            throw new OperationNotPermittedException("You can't change your own membership.");
        }
        if (removesActiveAdmin(membership, request) && activeAdmins.size() <= 1) {
            throw new LastCompanyAdminException();
        }

        if (request.companyRole() != null) {
            membership.changeRole(request.companyRole());
        }
        if (request.status() != null) {
            membership.changeStatus(request.status());
        }
        return MembershipResponse.from(membershipRepository.saveAndFlush(membership));
    }

    private static boolean removesActiveAdmin(CompanyMembership membership, UpdateMembershipRequest request) {
        boolean demoted = request.companyRole() != null && request.companyRole() != CompanyRole.COMPANY_ADMIN;
        boolean suspended = request.status() == MembershipStatus.SUSPENDED;
        return membership.isActiveCompanyAdmin() && (demoted || suspended);
    }
}
