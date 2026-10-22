package com.flowledger.cashmanagement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

// A correction of a recorded day's amounts. The sale date cannot change: a wrong date is a
// different day's record.
public record UpdateDailySaleRequest(
        @NotNull(message = "cashSalesAmount is required")
        @DecimalMin(value = "0.00", message = "cashSalesAmount must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "cashSalesAmount must have at most 17 digits before and 2 after the decimal point")
        BigDecimal cashSalesAmount,

        @NotNull(message = "cardSalesAmount is required")
        @DecimalMin(value = "0.00", message = "cardSalesAmount must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "cardSalesAmount must have at most 17 digits before and 2 after the decimal point")
        BigDecimal cardSalesAmount,

        // Required: every correction is recorded with its reason in the audit history.
        @NotBlank(message = "reason must not be blank")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,

        @NotNull(message = "version is required")
        Integer version
) {
}
