package com.flowledger.cashmanagement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One correction of a daily sale, from the audit history: who, when, why, and the amounts before and after. */
public record DailySaleCorrectionResponse(
        UUID correctedBy,
        Instant correctedAt,
        String reason,
        BigDecimal cashSalesBefore,
        BigDecimal cardSalesBefore,
        BigDecimal cashSalesAfter,
        BigDecimal cardSalesAfter
) {
}
