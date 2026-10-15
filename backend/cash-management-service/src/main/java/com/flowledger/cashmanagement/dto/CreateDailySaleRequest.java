package com.flowledger.cashmanagement.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

// The company, the recording user and the entry time come from the request path, the access
// token and the database; they are never accepted from the client.
public record CreateDailySaleRequest(
        @NotNull(message = "saleDate is required")
        LocalDate saleDate,

        @NotNull(message = "cashSalesAmount is required")
        @DecimalMin(value = "0.00", message = "cashSalesAmount must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "cashSalesAmount must have at most 17 digits before and 2 after the decimal point")
        BigDecimal cashSalesAmount,

        @NotNull(message = "cardSalesAmount is required")
        @DecimalMin(value = "0.00", message = "cardSalesAmount must not be negative")
        @Digits(integer = 17, fraction = 2,
                message = "cardSalesAmount must have at most 17 digits before and 2 after the decimal point")
        BigDecimal cardSalesAmount
) {
}
