package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyMembership;

import java.util.UUID;

/**
 * A member's display name, so screens can show who recorded or changed something. Only the name:
 * no email, role or status, since every member of the company may read it.
 */
public record MemberNameResponse(UUID userId, String fullName) {

    public static MemberNameResponse from(CompanyMembership membership) {
        return new MemberNameResponse(membership.getUser().getId(), membership.getUser().getFullName());
    }
}
