package com.flowledger.identity.controller;

import com.flowledger.identity.dto.CreateMembershipRequest;
import com.flowledger.identity.dto.CreateMembershipResponse;
import com.flowledger.identity.dto.MembershipResponse;
import com.flowledger.identity.dto.UpdateMembershipRequest;
import com.flowledger.identity.service.MembershipManagementService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/companies/{companyId}/memberships")
@RequiredArgsConstructor
public class MembershipController {

    private final MembershipManagementService membershipManagementService;

    @GetMapping
    public List<MembershipResponse> listMemberships(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return membershipManagementService.listMemberships(UUID.fromString(user.getSubject()), companyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateMembershipResponse createMembership(@AuthenticationPrincipal Jwt user,
                                                     @PathVariable UUID companyId,
                                                     @Valid @RequestBody CreateMembershipRequest request) {
        return membershipManagementService.createMembership(UUID.fromString(user.getSubject()), companyId, request);
    }

    @PatchMapping("/{membershipId}")
    public MembershipResponse updateMembership(@AuthenticationPrincipal Jwt user,
                                               @PathVariable UUID companyId,
                                               @PathVariable UUID membershipId,
                                               @RequestBody UpdateMembershipRequest request) {
        return membershipManagementService.updateMembership(
                UUID.fromString(user.getSubject()), companyId, membershipId, request);
    }
}
