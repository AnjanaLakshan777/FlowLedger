package com.flowledger.identity.controller;

import com.flowledger.identity.dto.MembershipAuthorizationResponse;
import com.flowledger.identity.service.MembershipAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal API for other FlowLedger services; protected by the internal security chain.
 * The user comes from the forwarded access token, never from request parameters, so a caller
 * can only ask about the user who is making the original request.
 */
@RestController
@RequestMapping("/internal/v1/identity/companies/{companyId}/membership")
@RequiredArgsConstructor
public class InternalMembershipController {

    private final MembershipAuthorizationService membershipAuthorizationService;

    @GetMapping
    public MembershipAuthorizationResponse checkMembership(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable UUID companyId) {
        return membershipAuthorizationService.checkMembership(UUID.fromString(jwt.getSubject()), companyId);
    }
}
