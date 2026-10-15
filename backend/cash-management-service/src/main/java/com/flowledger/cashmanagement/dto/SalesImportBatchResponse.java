package com.flowledger.cashmanagement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One historical sales import. Totals and the period cover the valid rows only. version is checked
 * when the import is approved or rejected.
 */
public record SalesImportBatchResponse(
        UUID id,
        String fileName,
        String status,
        String fileError,
        LocalDate periodStart,
        LocalDate periodEnd,
        int rowCount,
        int validRowCount,
        int invalidRowCount,
        int duplicateRowCount,
        BigDecimal cashSalesTotal,
        BigDecimal cardSalesTotal,
        UUID uploadedBy,
        Instant uploadedAt,
        UUID approvedBy,
        Instant approvedAt,
        String approvalNote,
        Integer importedRowCount,
        Integer excludedRowCount,
        UUID rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        int version
) {
}
