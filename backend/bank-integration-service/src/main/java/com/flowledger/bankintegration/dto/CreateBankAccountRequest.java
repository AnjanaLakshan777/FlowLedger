package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.BankAccountType;
import com.flowledger.bankintegration.entity.IntegrationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBankAccountRequest(
        @NotBlank(message = "bankName must not be blank")
        @Size(max = 160, message = "bankName must be at most 160 characters")
        String bankName,

        @NotBlank(message = "accountName must not be blank")
        @Size(max = 160, message = "accountName must be at most 160 characters")
        String accountName,

        @NotNull(message = "accountType is required")
        BankAccountType accountType,

        // Only the last four digits are accepted; the full account number is never sent or stored.
        @Pattern(regexp = "\\d{4}", message = "accountNumberLast4 must be exactly 4 digits")
        String accountNumberLast4,

        @NotNull(message = "integrationMode is required")
        IntegrationMode integrationMode
) {
}
