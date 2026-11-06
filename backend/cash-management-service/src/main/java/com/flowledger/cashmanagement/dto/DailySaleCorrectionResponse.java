package com.flowledger.cashmanagement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One correction of a daily sale, from the audit history: who, when, why, and the amounts and the
 * company-holiday mark before and after (false for corrections recorded before the mark existed).
 */
public record DailySaleCorrectionResponse(
        UUID correctedBy,
        Instant correctedAt,
        String reason,
        BigDecimal cashSalesBefore,
        BigDecimal cardSalesBefore,
        BigDecimal cashSalesAfter,
        BigDecimal cardSalesAfter,
        boolean companyHolidayBefore,
        boolean companyHolidayAfter
) {
}
