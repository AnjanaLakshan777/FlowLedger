package com.flowledger.payment.dto;

import com.flowledger.payment.entity.ChequeCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The editable fields of a cheque, for both adding and editing. {@code version} is required
 * when editing (optimistic locking) and ignored when adding. The company, bank account, status
 * and audit fields are never taken from the client.
 */
public record ChequeRequest(
        @NotNull(message = "chequeNumber is required")
        @Pattern(regexp = "\\d{6}", message = "chequeNumber must be exactly 6 digits")
        String chequeNumber,

        @NotBlank(message = "payeeName must not be blank")
        @Size(max = 200, message = "payeeName must be at most 200 characters")
        String payeeName,

        @NotNull(message = "category is required")
        ChequeCategory category,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        @Digits(integer = 17, fraction = 2,
                message = "amount must have at most 17 digits before and 2 after the decimal point")
        BigDecimal amount,

        @NotNull(message = "issueDate is required")
        LocalDate issueDate,

        @NotNull(message = "expectedClearingDate is required")
        LocalDate expectedClearingDate,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes,

        Integer version
) {
}
