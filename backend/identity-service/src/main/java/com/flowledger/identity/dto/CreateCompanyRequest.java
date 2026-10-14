package com.flowledger.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCompanyRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        @Size(max = 80, message = "registrationNumber must be at most 80 characters")
        String registrationNumber
) {
}
