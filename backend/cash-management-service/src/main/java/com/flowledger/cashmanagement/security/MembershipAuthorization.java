package com.flowledger.cashmanagement.security;

// Response of identity-service's internal membership check.
record MembershipAuthorization(boolean allowed, CompanyRole companyRole) {
}
