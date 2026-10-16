package com.flowledger.identity.controller;

import com.flowledger.identity.dto.CompanyResponse;
import com.flowledger.identity.dto.CreateCompanyRequest;
import com.flowledger.identity.dto.UpdateCompanyRequest;
import com.flowledger.identity.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse createCompany(@AuthenticationPrincipal Jwt user,
                                         @Valid @RequestBody CreateCompanyRequest request) {
        return companyService.createCompany(UUID.fromString(user.getSubject()), request);
    }

    @GetMapping("/{companyId}")
    public CompanyResponse getCompany(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return companyService.getCompany(UUID.fromString(user.getSubject()), companyId);
    }

    @PatchMapping("/{companyId}")
    public CompanyResponse updateCompany(@AuthenticationPrincipal Jwt user,
                                         @PathVariable UUID companyId,
                                         @Valid @RequestBody UpdateCompanyRequest request) {
        return companyService.updateCompany(UUID.fromString(user.getSubject()), companyId, request);
    }
}
