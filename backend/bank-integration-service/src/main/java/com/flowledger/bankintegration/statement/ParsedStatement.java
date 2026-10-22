package com.flowledger.bankintegration.statement;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * The result of parsing a statement, independent of the bank's file layout. Either it has
 * errors and nothing may be imported, or it has rows in a verified order with a consistent
 * running balance.
 *
 * @param lines all data rows in file order, including zero-amount rows
 * @param newestFirst whether the file lists the newest transaction first
 */
public record ParsedStatement(String format, List<StatementLine> lines, boolean newestFirst,
                              List<StatementError> errors) {

    public static ParsedStatement failed(String format, List<StatementError> errors) {
        return new ParsedStatement(format, List.of(), false, List.copyOf(errors));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    // Rows that become bank transactions, oldest first.
    public List<StatementLine> transactionsOldestFirst() {
        List<StatementLine> transactions = lines.stream().filter(line -> !line.isZeroAmount()).toList();
        return newestFirst ? transactions.reversed() : transactions;
    }

    public long zeroAmountCount() {
        return lines.stream().filter(StatementLine::isZeroAmount).count();
    }

    // The newest row; its running balance is the account balance at the end of the statement.
    public StatementLine latestLine() {
        return newestFirst ? lines.getFirst() : lines.getLast();
    }

    public LocalDate periodStart() {
        return lines.stream().map(StatementLine::transactionDate).min(Comparator.naturalOrder()).orElseThrow();
    }

    public LocalDate periodEnd() {
        return lines.stream().map(StatementLine::transactionDate).max(Comparator.naturalOrder()).orElseThrow();
    }
}
