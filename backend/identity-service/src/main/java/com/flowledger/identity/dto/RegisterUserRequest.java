package com.flowledger.identity.dto;

import com.flowledger.identity.entity.CompanyRole;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record RegisterUserRequest(
        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,

        @NotBlank(message = "fullName must not be blank")
        @Size(max = 160, message = "fullName must be at most 160 characters")
        String fullName,

        @NotBlank(message = "password must not be blank")
        @Size(min = 12, max = 72, message = "password must be between 12 and 72 characters")
        String password,

        @NotNull(message = "companyId is required")
        UUID companyId,

        @NotNull(message = "companyRole is required")
        CompanyRole companyRole
) {

    // BCrypt only hashes the first 72 bytes, and Spring Security rejects longer input,
    // so non-ASCII passwords can hit the limit before 72 characters.
    @AssertTrue(message = "password must be at most 72 bytes")
    boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // Keeps the password out of logs and assertion messages.
    @Override
    public String toString() {
        return "RegisterUserRequest[email=" + email + ", fullName=" + fullName
                + ", companyId=" + companyId + ", companyRole=" + companyRole + "]";
    }
}
