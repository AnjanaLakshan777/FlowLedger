package com.flowledger.bankintegration.security;

// Response of identity-service's internal membership check.
record MembershipAuthorization(boolean allowed, CompanyRole companyRole) {
}
