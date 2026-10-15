package com.flowledger.identity.service;

import com.flowledger.identity.dto.CompanyResponse;
import com.flowledger.identity.dto.CreateCompanyRequest;
import com.flowledger.identity.entity.Company;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    // Creating companies is a platform-wide operation, reserved for system admins.
    @Transactional
    public CompanyResponse createCompany(UUID actorId, CreateCompanyRequest request) {
        adminAuthorizationService.requirePlatformAdmin(actorId);

        Company company = new Company(
                request.name().trim(),
                trimToNull(request.registrationNumber()),
                CompanyStatus.ACTIVE
        );
        return CompanyResponse.from(companyRepository.save(company));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
