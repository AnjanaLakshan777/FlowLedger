package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyRole;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

// A company admin adds a user to their company. temporaryPassword is required only when the email
// has no account yet; an existing account keeps its own name and password.
public record CreateMembershipRequest(
        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,

        @NotBlank(message = "fullName must not be blank")
        @Size(max = 160, message = "fullName must be at most 160 characters")
        String fullName,

        @NotNull(message = "companyRole is required")
        CompanyRole companyRole,

        @Size(min = 12, max = 72, message = "temporaryPassword must be between 12 and 72 characters")
        String temporaryPassword
) {

    // BCrypt only hashes the first 72 bytes, as in RegisterUserRequest.
    @AssertTrue(message = "temporaryPassword must be at most 72 bytes")
    boolean isTemporaryPasswordWithinByteLimit() {
        return temporaryPassword == null || temporaryPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // Keeps the password out of logs and assertion messages.
    @Override
    public String toString() {
        return "CreateMembershipRequest[email=" + email + ", fullName=" + fullName
                + ", companyRole=" + companyRole + "]";
    }
}
