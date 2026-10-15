package com.flowledger.forecast.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// An ISSUED cheque expected to clear within the forecast horizon.
public record ExpectedCheque(
        UUID chequeId,
        String chequeNumber,
        BigDecimal amount,
        LocalDate expectedClearingDate,
        int sourceVersion
) {
}
