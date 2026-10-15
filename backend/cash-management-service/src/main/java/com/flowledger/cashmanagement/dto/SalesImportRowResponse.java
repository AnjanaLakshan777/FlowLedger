package com.flowledger.cashmanagement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One data row of an import. Values are null where the cell was blank or unreadable; raw holds the
 * cells as written for rows that are not valid.
 */
public record SalesImportRowResponse(
        int rowNumber,
        LocalDate saleDate,
        BigDecimal cashSalesAmount,
        BigDecimal cardSalesAmount,
        String status,
        boolean duplicateDate,
        List<Map<String, String>> errors,
        Map<String, String> raw
) {
}
