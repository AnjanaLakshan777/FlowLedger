package com.flowledger.payment.dto;

import com.flowledger.payment.entity.Cheque;
import com.flowledger.payment.entity.ChequeCategory;
import com.flowledger.payment.entity.ChequeStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ChequeResponse(
        UUID id,
        UUID companyId,
        UUID bankAccountId,
        String chequeNumber,
        String payeeName,
        ChequeCategory category,
        BigDecimal amount,
        LocalDate issueDate,
        LocalDate expectedClearingDate,
        ChequeStatus status,
        String notes,
        UUID createdBy,
        Instant createdAt,
        UUID updatedBy,
        Instant updatedAt,
        int version
) {

    public static ChequeResponse from(Cheque cheque) {
        return new ChequeResponse(
                cheque.getId(),
                cheque.getCompanyId(),
                cheque.getBankAccountId(),
                cheque.getChequeNumber(),
                cheque.getPayeeName(),
                cheque.getCategory(),
                cheque.getAmount(),
                cheque.getIssueDate(),
                cheque.getExpectedClearingDate(),
                cheque.getStatus(),
                cheque.getNotes(),
                cheque.getCreatedBy(),
                cheque.getCreatedAt(),
                cheque.getUpdatedBy(),
                cheque.getUpdatedAt(),
                cheque.getVersion()
        );
    }
}
