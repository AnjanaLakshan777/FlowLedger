package com.flowledger.cashmanagement.security;

// Response of identity-service's internal membership check. companyRole is set for an active
// membership; platformAdmin is true for an active system admin and an active company. Daily
// sales grant nothing for platformAdmin alone: only company roles are checked.
record MembershipAuthorization(boolean allowed, CompanyRole companyRole, boolean platformAdmin) {
}
