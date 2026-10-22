package com.flowledger.identity.controller;

import com.flowledger.identity.dto.MemberNameResponse;
import com.flowledger.identity.service.MembershipManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Members' names for every member of the company, so screens can show who recorded something.
@RestController
@RequestMapping("/api/v1/identity/companies/{companyId}/member-names")
@RequiredArgsConstructor
public class MemberNameController {

    private final MembershipManagementService membershipManagementService;

    @GetMapping
    public List<MemberNameResponse> listMemberNames(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return membershipManagementService.listMemberNames(UUID.fromString(user.getSubject()), companyId);
    }
}
