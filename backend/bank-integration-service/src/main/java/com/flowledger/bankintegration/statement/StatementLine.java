package com.flowledger.bankintegration.statement;

import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.entity.TransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed statement row. The amount is never negative; the direction carries the sign.
 * A zero amount is possible (for example a card verification) and is not imported.
 * {@code category} is UNCLASSIFIED for a bank's own statement; the FlowLedger monthly template
 * carries the category the user gave, with {@code otherDescription} for OTHER.
 */
public record StatementLine(
        int lineNumber,
        LocalDate transactionDate,
        TransactionDirection direction,
        BigDecimal amount,
        String description,
        BigDecimal runningBalance,
        String chequeNumber,
        String branchCode,
        TransactionCategory category,
        String otherDescription
) {

    public StatementLine(int lineNumber, LocalDate transactionDate, TransactionDirection direction, BigDecimal amount,
                         String description, BigDecimal runningBalance, String chequeNumber, String branchCode) {
        this(lineNumber, transactionDate, direction, amount, description, runningBalance, chequeNumber, branchCode,
                TransactionCategory.UNCLASSIFIED, null);
    }

    public boolean isZeroAmount() {
        return amount.signum() == 0;
    }

    // The balance change this row causes.
    public BigDecimal signedAmount() {
        return direction == TransactionDirection.CREDIT ? amount : amount.negate();
    }

    public boolean isClassified() {
        return category != TransactionCategory.UNCLASSIFIED;
    }
}
