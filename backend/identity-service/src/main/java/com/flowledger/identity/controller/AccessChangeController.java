package com.flowledger.identity.controller;

import com.flowledger.identity.dto.AccessChangeResponse;
import com.flowledger.identity.service.AccessAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/companies/{companyId}/access-changes")
@RequiredArgsConstructor
public class AccessChangeController {

    private final AccessAuditService accessAuditService;

    @GetMapping
    public List<AccessChangeResponse> listAccessChanges(@AuthenticationPrincipal Jwt user,
                                                        @PathVariable UUID companyId) {
        return accessAuditService.listAccessChanges(UUID.fromString(user.getSubject()), companyId);
    }
}
