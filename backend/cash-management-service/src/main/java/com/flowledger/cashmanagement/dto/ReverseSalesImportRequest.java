package com.flowledger.cashmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Why an approved import is reversed, kept in the audit history; version is the one the user reviewed.
public record ReverseSalesImportRequest(
        @NotNull(message = "version is required")
        Integer version,
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason
) {
}
