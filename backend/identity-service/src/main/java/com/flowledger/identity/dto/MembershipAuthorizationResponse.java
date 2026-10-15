package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyRole;

/**
 * Answer to an internal membership check. {@code companyRole} is set only for an active
 * membership; {@code platformAdmin} is true for an active system admin and an active company,
 * with or without a membership. Which operations either grants is decided by the calling service.
 */
public record MembershipAuthorizationResponse(boolean allowed, CompanyRole companyRole, boolean platformAdmin) {

    public static MembershipAuthorizationResponse denied() {
        return new MembershipAuthorizationResponse(false, null, false);
    }
}
