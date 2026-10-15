package com.flowledger.payment.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

// The fields a user enters for a cheque. The amount is held at two decimal places, so an
// unchanged amount compares equal; it is validated to at most two, so setScale never rounds.
public record ChequeDetails(
        String chequeNumber,
        String payeeName,
        ChequeCategory category,
        BigDecimal amount,
        LocalDate issueDate,
        LocalDate expectedClearingDate,
        String notes
) {

    public ChequeDetails {
        amount = amount.setScale(2);
    }
}
