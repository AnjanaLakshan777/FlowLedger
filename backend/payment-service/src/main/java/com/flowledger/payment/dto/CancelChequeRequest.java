package com.flowledger.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CancelChequeRequest(
        // Required: every cancellation is recorded with its reason in the audit history.
        @NotBlank(message = "reason must not be blank")
        @Size(max = 1000, message = "reason must be at most 1000 characters")
        String reason,

        @NotNull(message = "version is required")
        Integer version
) {
}
