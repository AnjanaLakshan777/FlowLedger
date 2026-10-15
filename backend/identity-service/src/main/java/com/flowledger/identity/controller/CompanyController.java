package com.flowledger.identity.controller;

import com.flowledger.identity.dto.CompanyResponse;
import com.flowledger.identity.dto.CreateCompanyRequest;
import com.flowledger.identity.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
}
