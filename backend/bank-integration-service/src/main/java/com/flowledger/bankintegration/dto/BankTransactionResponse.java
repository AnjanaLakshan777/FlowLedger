package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.BankTransaction;
import com.flowledger.bankintegration.entity.ClassificationMethod;
import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.entity.TransactionDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BankTransactionResponse(
        UUID id,
        UUID companyId,
        UUID bankAccountId,
        LocalDate transactionDate,
        TransactionDirection direction,
        BigDecimal amount,
        String currency,
        String description,
        BigDecimal runningBalance,
        String chequeNumber,
        String branchCode,
        TransactionCategory category,
        ClassificationMethod classificationMethod,
        UUID classifiedBy,
        Instant classifiedAt,
        UUID sourceImportBatchId,
        Instant createdAt,
        Instant updatedAt
) {

    public static BankTransactionResponse from(BankTransaction transaction) {
        return new BankTransactionResponse(
                transaction.getId(),
                transaction.getCompanyId(),
                transaction.getBankAccountId(),
                transaction.getTransactionDate(),
                transaction.getDirection(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getRunningBalance(),
                transaction.getChequeNumber(),
                transaction.getBranchCode(),
                transaction.getCategory(),
                transaction.getClassificationMethod(),
                transaction.getClassifiedBy(),
                transaction.getClassifiedAt(),
                transaction.getSourceImportBatchId(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }
}
