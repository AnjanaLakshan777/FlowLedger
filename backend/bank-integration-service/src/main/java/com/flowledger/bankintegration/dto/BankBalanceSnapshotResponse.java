package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.BankBalanceSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BankBalanceSnapshotResponse(
        UUID id,
        UUID companyId,
        UUID bankAccountId,
        BigDecimal balanceAmount,
        String currency,
        Instant balanceAsOf,
        String sourceType,
        UUID sourceImportBatchId,
        Instant createdAt
) {

    public static BankBalanceSnapshotResponse from(BankBalanceSnapshot snapshot) {
        return new BankBalanceSnapshotResponse(
                snapshot.getId(),
                snapshot.getCompanyId(),
                snapshot.getBankAccountId(),
                snapshot.getBalanceAmount(),
                snapshot.getCurrency(),
                snapshot.getBalanceAsOf(),
                snapshot.getSourceType(),
                snapshot.getSourceImportBatchId(),
                snapshot.getCreatedAt()
        );
    }
}
