package com.flowledger.identity.dto;

// accountCreated is false when the email already had an account, which keeps its password.
public record CreateMembershipResponse(MembershipResponse membership, boolean accountCreated) {
}
