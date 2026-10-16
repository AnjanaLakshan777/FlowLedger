package com.flowledger.cashmanagement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Approved historical sales between from and to (sale dates, inclusive), one entry per sale date,
 * oldest first. Only valid rows of approved imports are listed, including days approved with zero
 * sales; a date without an entry is unknown, not zero. These are sales records by sale date, never
 * bank inflows.
 */
public record SalesHistoryResponse(LocalDate from, LocalDate to, List<Day> days) {

    public record Day(LocalDate saleDate, BigDecimal cashSalesAmount, BigDecimal cardSalesAmount, UUID importBatchId) {
    }
}
