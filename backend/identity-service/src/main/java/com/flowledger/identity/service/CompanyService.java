package com.flowledger.identity.service;

import com.flowledger.identity.dto.CompanyResponse;
import com.flowledger.identity.dto.CreateCompanyRequest;
import com.flowledger.identity.dto.UpdateCompanyRequest;
import com.flowledger.identity.entity.Company;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.exception.CompanyNotFoundException;
import com.flowledger.identity.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final AccessAuditService accessAuditService;

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

    @Transactional(readOnly = true)
    public CompanyResponse getCompany(UUID actorId, UUID companyId) {
        adminAuthorizationService.requireCompanyAccess(actorId, companyId);
        return CompanyResponse.from(companyRepository.findById(companyId).orElseThrow(CompanyNotFoundException::new));
    }

    // Company administration. Each changed field is recorded as its own access change.
    @Transactional
    public CompanyResponse updateCompany(UUID actorId, UUID companyId, UpdateCompanyRequest request) {
        adminAuthorizationService.requireCompanyAdministration(actorId, companyId);
        Company company = companyRepository.findById(companyId).orElseThrow(CompanyNotFoundException::new);

        String name = request.name().trim();
        String registrationNumber = trimToNull(request.registrationNumber());
        String previousName = company.getName();
        String previousRegistrationNumber = company.getRegistrationNumber();
        company.changeDetails(name, registrationNumber);
        Company saved = companyRepository.saveAndFlush(company);

        recordIfChanged(actorId, companyId, "name", previousName, name);
        recordIfChanged(actorId, companyId, "registrationNumber", previousRegistrationNumber, registrationNumber);
        return CompanyResponse.from(saved);
    }

    private void recordIfChanged(UUID actorId, UUID companyId, String field, String from, String to) {
        if (Objects.equals(from, to)) {
            return;
        }
        // HashMap, not Map.of: a cleared or first-time value is null.
        Map<String, Object> details = new HashMap<>();
        details.put("field", field);
        details.put("from", from);
        details.put("to", to);
        accessAuditService.record(companyId, actorId, AccessAuditService.COMPANY_UPDATED, "COMPANY", companyId, details);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
