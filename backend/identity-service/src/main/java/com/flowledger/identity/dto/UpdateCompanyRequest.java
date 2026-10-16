package com.flowledger.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Only the name and registration number can be edited; currency and time zone are fixed for the
// MVP. A blank registration number clears it.
public record UpdateCompanyRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        @Size(max = 80, message = "registrationNumber must be at most 80 characters")
        String registrationNumber
) {
}
