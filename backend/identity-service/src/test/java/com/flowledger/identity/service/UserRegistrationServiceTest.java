package com.flowledger.identity.service;

import com.flowledger.identity.dto.RegisterUserRequest;
import com.flowledger.identity.entity.Company;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.exception.EmailAlreadyRegisteredException;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.CompanyRepository;
import com.flowledger.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRegistrationServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final CompanyMembershipRepository membershipRepository = mock(CompanyMembershipRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final UserRegistrationService registrationService = new UserRegistrationService(
            userRepository, companyRepository, membershipRepository, passwordEncoder);

    @Test
    void shouldReportDuplicateEmailWhenConcurrentRegistrationWinsTheUniqueIndex() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId))
                .thenReturn(Optional.of(new Company("ABC Trading (Pvt) Ltd", null, CompanyStatus.ACTIVE)));
        when(userRepository.existsByEmailIgnoreCase("nimal@example.com")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_users_email_lower"));

        RegisterUserRequest request = new RegisterUserRequest(
                "nimal@example.com", "Nimal Perera", "correct-horse-battery", companyId, CompanyRole.ACCOUNTANT);

        assertThatThrownBy(() -> registrationService.register(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(membershipRepository, never()).save(any());
    }
}
