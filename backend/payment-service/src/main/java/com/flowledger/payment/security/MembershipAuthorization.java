package com.flowledger.payment.security;

// Response of identity-service's internal membership check. companyRole is set for an active
// membership; platformAdmin is true for an active system admin and an active company. Cheques
// are financial business data, so platformAdmin alone grants nothing here: only company roles count.
record MembershipAuthorization(boolean allowed, CompanyRole companyRole, boolean platformAdmin) {
}
