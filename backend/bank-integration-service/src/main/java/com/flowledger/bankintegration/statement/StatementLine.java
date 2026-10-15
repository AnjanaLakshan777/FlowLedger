package com.flowledger.bankintegration.statement;

import com.flowledger.bankintegration.entity.TransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed statement row. The amount is never negative; the direction carries the sign.
 * A zero amount is possible (for example a card verification) and is not imported.
 */
public record StatementLine(
        int lineNumber,
        LocalDate transactionDate,
        TransactionDirection direction,
        BigDecimal amount,
        String description,
        BigDecimal runningBalance,
        String chequeNumber,
        String branchCode
) {

    public boolean isZeroAmount() {
        return amount.signum() == 0;
    }

    // The balance change this row causes.
    public BigDecimal signedAmount() {
        return direction == TransactionDirection.CREDIT ? amount : amount.negate();
    }
}
