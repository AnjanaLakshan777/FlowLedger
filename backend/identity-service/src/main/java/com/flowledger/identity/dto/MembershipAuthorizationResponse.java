package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyRole;

/**
 * Answer to an internal membership check. The role is only included when access is allowed.
 */
public record MembershipAuthorizationResponse(boolean allowed, CompanyRole companyRole) {

    public static MembershipAuthorizationResponse allowed(CompanyRole companyRole) {
        return new MembershipAuthorizationResponse(true, companyRole);
    }

    public static MembershipAuthorizationResponse denied() {
        return new MembershipAuthorizationResponse(false, null);
    }
}
