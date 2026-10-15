package com.flowledger.forecast.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One forecast day. {@code inflow} is null when inflows could not be estimated; the closing
 * balance then includes cheque outflows only.
 */
public record DailyProjection(
        LocalDate date,
        ExpectedInflow inflow,
        BigDecimal chequeOutflows,
        int chequeCount,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        boolean cashShortage
) {
}
