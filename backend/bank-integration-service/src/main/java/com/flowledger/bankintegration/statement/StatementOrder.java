package com.flowledger.bankintegration.statement;

import java.time.LocalDate;
import java.util.List;

/**
 * Order and running-balance checks shared by every statement layout. Rows must be in date order
 * (newest or oldest first) and each running balance must follow from the previous balance and the
 * row's amount, so the latest balance can be trusted and no rows are missing.
 */
final class StatementOrder {

    private StatementOrder() {
    }

    /**
     * Whether the rows are newest first, checking the dates and the balance chain. Errors are added
     * when the order is wrong or cannot be decided; {@code balanceField} names the column to report.
     */
    static boolean check(List<StatementLine> lines, String dateField, String balanceField, StatementErrors errors) {
        boolean newestFirst = isNewestFirst(lines, balanceField, errors);
        if (!errors.any()) {
            checkDateOrder(lines, newestFirst, dateField, errors);
        }
        if (!errors.any()) {
            checkRunningBalances(lines, newestFirst, balanceField, errors);
        }
        return newestFirst;
    }

    private static boolean isNewestFirst(List<StatementLine> lines, String balanceField, StatementErrors errors) {
        LocalDate first = lines.getFirst().transactionDate();
        LocalDate last = lines.getLast().transactionDate();
        if (!first.equals(last)) {
            return first.isAfter(last);
        }
        // All rows share one date, so only the running balances can show the order.
        boolean newestFirst = balancesFollow(lines, true, balanceField);
        boolean oldestFirst = balancesFollow(lines, false, balanceField);
        if (newestFirst && oldestFirst && lines.size() > 1) {
            errors.add(null, null, "The order of the transactions cannot be determined from the statement.");
        }
        return newestFirst || !oldestFirst;
    }

    private static void checkDateOrder(List<StatementLine> lines, boolean newestFirst, String dateField,
                                       StatementErrors errors) {
        for (int i = 1; i < lines.size(); i++) {
            LocalDate previous = lines.get(i - 1).transactionDate();
            LocalDate current = lines.get(i).transactionDate();
            if (newestFirst ? current.isAfter(previous) : current.isBefore(previous)) {
                errors.add(lines.get(i).lineNumber(), dateField, "Transactions are not in date order.");
                return;
            }
        }
    }

    private static void checkRunningBalances(List<StatementLine> lines, boolean newestFirst, String balanceField,
                                             StatementErrors errors) {
        for (int i = 1; i < lines.size(); i++) {
            StatementLine older = newestFirst ? lines.get(i) : lines.get(i - 1);
            StatementLine newer = newestFirst ? lines.get(i - 1) : lines.get(i);
            if (older.runningBalance().add(newer.signedAmount()).compareTo(newer.runningBalance()) != 0) {
                errors.add(newer.lineNumber(), balanceField,
                        "Running Balance does not follow from the previous balance and this amount; rows may be missing.");
                return;
            }
        }
    }

    private static boolean balancesFollow(List<StatementLine> lines, boolean newestFirst, String balanceField) {
        StatementErrors ignored = new StatementErrors();
        checkRunningBalances(lines, newestFirst, balanceField, ignored);
        return !ignored.any();
    }
}
