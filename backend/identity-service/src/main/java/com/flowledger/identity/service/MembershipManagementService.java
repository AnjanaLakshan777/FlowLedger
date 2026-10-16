package com.flowledger.identity.service;

import com.flowledger.identity.dto.CreateMembershipRequest;
import com.flowledger.identity.dto.CreateMembershipResponse;
import com.flowledger.identity.dto.MembershipResponse;
import com.flowledger.identity.dto.UpdateMembershipRequest;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.Company;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.exception.CompanyNotFoundException;
import com.flowledger.identity.exception.EmailAlreadyRegisteredException;
import com.flowledger.identity.exception.InvalidMembershipUpdateException;
import com.flowledger.identity.exception.LastCompanyAdminException;
import com.flowledger.identity.exception.MembershipAlreadyExistsException;
import com.flowledger.identity.exception.MembershipNotFoundException;
import com.flowledger.identity.exception.OperationNotPermittedException;
import com.flowledger.identity.exception.TemporaryPasswordRequiredException;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.CompanyRepository;
import com.flowledger.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Company user administration: a company admin for their own company, a system admin for any
 * company. Only company roles can be assigned here; platform roles are never granted via the API.
 */
@Service
@RequiredArgsConstructor
public class MembershipManagementService {

    private static final String MEMBERSHIP = "MEMBERSHIP";

    private final CompanyMembershipRepository membershipRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AccessAuditService accessAuditService;

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

    /**
     * Adds a user to the company with an active membership. A new email gets an active account
     * with the admin's temporary password, since there is no email delivery yet. An existing
     * account only gains the membership and keeps its own name, password and account status.
     */
    @Transactional
    public CreateMembershipResponse createMembership(UUID actorId, UUID companyId, CreateMembershipRequest request) {
        adminAuthorizationService.requireCompanyAdministration(actorId, companyId);
        Company company = companyRepository.findById(companyId).orElseThrow(CompanyNotFoundException::new);

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> existing = userRepository.findByEmailIgnoreCase(email);
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (membershipRepository.findWithUserAndCompany(companyId, user.getId()).isPresent()) {
                throw new MembershipAlreadyExistsException();
            }
        } else {
            if (request.temporaryPassword() == null) {
                throw new TemporaryPasswordRequiredException();
            }
            try {
                user = userRepository.saveAndFlush(new User(email, request.fullName().trim(),
                        passwordEncoder.encode(request.temporaryPassword()), AccountStatus.ACTIVE));
            } catch (DataIntegrityViolationException e) {
                // A concurrent request created an account for the same email after the lookup.
                throw new EmailAlreadyRegisteredException();
            }
        }

        CompanyMembership membership;
        try {
            membership = membershipRepository.saveAndFlush(new CompanyMembership(
                    company, user, request.companyRole(), MembershipStatus.ACTIVE, actorId));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request added the same user to the company after the check above.
            throw new MembershipAlreadyExistsException();
        }

        accessAuditService.record(companyId, actorId, AccessAuditService.MEMBERSHIP_CREATED, MEMBERSHIP,
                membership.getId(), Map.of(
                        "userId", user.getId().toString(),
                        "to", request.companyRole().name(),
                        "accountCreated", existing.isEmpty()));
        return new CreateMembershipResponse(MembershipResponse.from(membership), existing.isEmpty());
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

        CompanyRole previousRole = membership.getCompanyRole();
        MembershipStatus previousStatus = membership.getStatus();
        if (request.companyRole() != null) {
            membership.changeRole(request.companyRole());
        }
        if (request.status() != null) {
            membership.changeStatus(request.status());
        }
        CompanyMembership saved = membershipRepository.saveAndFlush(membership);

        // Only real changes are recorded; setting the current value again is not an access change.
        String userId = membership.getUser().getId().toString();
        if (saved.getCompanyRole() != previousRole) {
            accessAuditService.record(companyId, actorId, AccessAuditService.MEMBERSHIP_ROLE_CHANGED, MEMBERSHIP,
                    membershipId, Map.of("userId", userId, "from", previousRole.name(), "to", saved.getCompanyRole().name()));
        }
        if (saved.getStatus() != previousStatus) {
            accessAuditService.record(companyId, actorId, AccessAuditService.MEMBERSHIP_STATUS_CHANGED, MEMBERSHIP,
                    membershipId, Map.of("userId", userId, "from", previousStatus.name(), "to", saved.getStatus().name()));
        }
        return MembershipResponse.from(saved);
    }

    private static boolean removesActiveAdmin(CompanyMembership membership, UpdateMembershipRequest request) {
        boolean demoted = request.companyRole() != null && request.companyRole() != CompanyRole.COMPANY_ADMIN;
        boolean suspended = request.status() == MembershipStatus.SUSPENDED;
        return membership.isActiveCompanyAdmin() && (demoted || suspended);
    }
}
