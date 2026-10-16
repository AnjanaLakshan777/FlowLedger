package com.flowledger.bankintegration.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// A balance the bank confirmed, typed in for an account whose connection is MANUAL. It can be
// negative when the bank allows an overdraft.
public record ManualBalanceRequest(
        @NotNull(message = "balanceAmount is required")
        @Digits(integer = 17, fraction = 2, message = "balanceAmount must have at most two decimal places")
        BigDecimal balanceAmount,

        @NotNull(message = "balanceAsOf is required")
        OffsetDateTime balanceAsOf
) {
}
