package com.flowledger.identity.service;

import com.flowledger.identity.dto.RegisterUserRequest;
import com.flowledger.identity.dto.RegistrationResponse;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.Company;
import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.entity.MembershipStatus;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.exception.CompanyNotFoundException;
import com.flowledger.identity.exception.EmailAlreadyRegisteredException;
import com.flowledger.identity.exception.RoleNotAllowedException;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.CompanyRepository;
import com.flowledger.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    // Registration is unauthenticated, so callers may only ask for non-privileged roles.
    // COMPANY_ADMIN and FINANCE_CONTROLLER have to be granted by an authenticated company admin.
    private static final Set<CompanyRole> SELF_REGISTRATION_ROLES =
            EnumSet.of(CompanyRole.ACCOUNTANT, CompanyRole.CASHIER);

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegistrationResponse register(RegisterUserRequest request) {
        if (!SELF_REGISTRATION_ROLES.contains(request.companyRole())) {
            throw new RoleNotAllowedException(request.companyRole());
        }

        Company company = companyRepository.findById(request.companyId())
                .filter(found -> found.getStatus() == CompanyStatus.ACTIVE)
                .orElseThrow(CompanyNotFoundException::new);

        String email = request.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        // The account stays PENDING until the email address is verified.
        User user = new User(email, request.fullName().trim(),
                passwordEncoder.encode(request.password()), AccountStatus.PENDING);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request registered the same email after the check above.
            throw new EmailAlreadyRegisteredException();
        }

        // Self-registered members get no company access until a company admin activates them.
        CompanyMembership membership = membershipRepository.save(
                new CompanyMembership(company, user, request.companyRole(), MembershipStatus.INVITED));

        return RegistrationResponse.from(user, membership);
    }
}
