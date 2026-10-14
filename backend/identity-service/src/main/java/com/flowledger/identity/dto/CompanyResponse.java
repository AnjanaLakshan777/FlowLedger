package com.flowledger.identity.dto;

import com.flowledger.identity.entity.Company;
import com.flowledger.identity.entity.CompanyStatus;

import java.time.Instant;
import java.util.UUID;

public record CompanyResponse(
        UUID id,
        String name,
        String registrationNumber,
        String defaultCurrency,
        String timezone,
        CompanyStatus status,
        Instant createdAt
) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getRegistrationNumber(),
                company.getDefaultCurrency(),
                company.getTimezone(),
                company.getStatus(),
                company.getCreatedAt()
        );
    }
}
