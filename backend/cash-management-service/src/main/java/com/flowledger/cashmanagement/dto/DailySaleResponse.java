package com.flowledger.cashmanagement.dto;

import com.flowledger.cashmanagement.entity.DailySale;
import com.flowledger.cashmanagement.entity.EntrySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DailySaleResponse(
        UUID id,
        UUID companyId,
        LocalDate saleDate,
        BigDecimal cashSalesAmount,
        BigDecimal cardSalesAmount,
        BigDecimal totalSalesAmount,
        EntrySource entrySource,
        UUID recordedBy,
        Instant recordedAt,
        // Set once the day has been corrected.
        UUID updatedBy,
        Instant updatedAt,
        int version,
        // The reason given with the latest correction, in lists only; null otherwise.
        String correctionReason
) {

    public static DailySaleResponse from(DailySale sale) {
        return from(sale, null);
    }

    public static DailySaleResponse from(DailySale sale, String correctionReason) {
        return new DailySaleResponse(
                sale.getId(),
                sale.getCompanyId(),
                sale.getSaleDate(),
                sale.getCashSalesAmount(),
                sale.getCardSalesAmount(),
                sale.getTotalSalesAmount(),
                sale.getEntrySource(),
                sale.getRecordedBy(),
                sale.getRecordedAt(),
                sale.getUpdatedBy(),
                sale.getUpdatedAt(),
                sale.getVersion(),
                correctionReason
        );
    }
}
