package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.TransactionCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ClassifyTransactionRequest(
        @NotNull(message = "category is required")
        TransactionCategory category,

        // Required for OTHER: what the transaction was. Ignored for every other category.
        @Size(max = 120, message = "otherDescription must be at most 120 characters")
        String otherDescription,

        // Optional explanation, kept in the audit history.
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason
) {
}
