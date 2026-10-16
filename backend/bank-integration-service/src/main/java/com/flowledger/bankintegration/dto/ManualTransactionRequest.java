package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.entity.TransactionDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

// One transaction typed in from the bank's own record, for an account whose connection is MANUAL.
// category is optional: without it the transaction is UNCLASSIFIED, as an imported one would be.
public record ManualTransactionRequest(
        @NotNull(message = "transactionDate is required")
        LocalDate transactionDate,

        @NotNull(message = "direction is required")
        TransactionDirection direction,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "amount must have at most two decimal places")
        BigDecimal amount,

        @NotBlank(message = "description must not be blank")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Pattern(regexp = "\\d{1,40}", message = "chequeNumber must contain only digits")
        String chequeNumber,

        TransactionCategory category,

        // Required when category is OTHER: what the transaction was.
        @Size(max = 120, message = "otherDescription must be at most 120 characters")
        String otherDescription
) {
}
