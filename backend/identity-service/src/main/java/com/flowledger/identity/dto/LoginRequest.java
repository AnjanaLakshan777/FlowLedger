package com.flowledger.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email must not be blank")
        String email,

        @NotBlank(message = "password must not be blank")
        String password
) {

    // Keeps the password out of logs and assertion messages.
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}
