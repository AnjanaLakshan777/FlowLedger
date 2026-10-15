package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;

// Both fields are optional; at least one must be given. Only company roles exist here, so a
// platform role such as SYSTEM_ADMIN cannot be requested.
public record UpdateMembershipRequest(CompanyRole companyRole, MembershipStatus status) {
}
