package com.flowledger.cashmanagement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recorded sales between from and to (sale dates, inclusive), one entry per sale date, oldest first:
 * valid rows of approved imports, including days approved with zero sales (entrySource
 * HISTORICAL_IMPORT, with importBatchId), and daily sales entered by hand (entrySource UI, no
 * importBatchId). Pending, rejected, and invalid import rows are never listed. A date without an
 * entry is unknown, not zero. These are sales records by sale date, never bank inflows.
 */
public record SalesHistoryResponse(LocalDate from, LocalDate to, List<Day> days) {

    public record Day(LocalDate saleDate, BigDecimal cashSalesAmount, BigDecimal cardSalesAmount, UUID importBatchId,
                      String entrySource) {
    }
}
