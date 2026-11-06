package com.flowledger.identity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

/**
 * Replaces the password of an account, proven by its email and current password (for a new
 * account, the temporary password its company admin set). No access token is needed, as an
 * account that must change its password is never given one.
 */
public record PasswordChangeRequest(
        @NotBlank(message = "email must not be blank")
        String email,

        @NotBlank(message = "currentPassword must not be blank")
        String currentPassword,

        @NotBlank(message = "newPassword must not be blank")
        @Size(min = 12, max = 72, message = "newPassword must be between 12 and 72 characters")
        String newPassword
) {

    // BCrypt only hashes the first 72 bytes, and Spring Security rejects longer input.
    @AssertTrue(message = "newPassword must be at most 72 bytes")
    boolean isNewPasswordWithinByteLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // Keeps the passwords out of logs and assertion messages.
    @Override
    public String toString() {
        return "PasswordChangeRequest[email=" + email + "]";
    }
}
