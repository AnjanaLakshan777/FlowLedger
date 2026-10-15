package com.flowledger.bankintegration.entity;

/**
 * What a bank transaction was, for Forecast vs Actual: actual inflows are cash and card sales
 * receipts, actual outflows are cheque payments. Imported transactions start UNCLASSIFIED;
 * the statement alone does not show what a credit or debit was.
 */
public enum TransactionCategory {
    CASH_SALES_DEPOSIT,
    CARD_SALES_SETTLEMENT,
    CHEQUE_PAYMENT,
    OTHER,
    UNCLASSIFIED;

    // Sales receipts are money in and cheque payments money out; the database checks the same.
    public boolean fits(TransactionDirection direction) {
        return switch (this) {
            case CASH_SALES_DEPOSIT, CARD_SALES_SETTLEMENT -> direction == TransactionDirection.CREDIT;
            case CHEQUE_PAYMENT -> direction == TransactionDirection.DEBIT;
            case OTHER, UNCLASSIFIED -> true;
        };
    }
}
