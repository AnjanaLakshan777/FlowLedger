package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.BankAccount;
import com.flowledger.bankintegration.entity.BankAccountStatus;
import com.flowledger.bankintegration.entity.BankAccountType;
import com.flowledger.bankintegration.entity.IntegrationMode;

import java.time.Instant;
import java.util.UUID;

public record BankAccountResponse(
        UUID id,
        UUID companyId,
        String bankName,
        String branchName,
        String accountName,
        BankAccountType accountType,
        String accountNumberLast4,
        String currency,
        IntegrationMode integrationMode,
        BankAccountStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static BankAccountResponse from(BankAccount account) {
        return new BankAccountResponse(
                account.getId(),
                account.getCompanyId(),
                account.getBankName(),
                account.getBranchName(),
                account.getAccountName(),
                account.getAccountType(),
                account.getAccountNumberLast4(),
                account.getCurrency(),
                account.getIntegrationMode(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
