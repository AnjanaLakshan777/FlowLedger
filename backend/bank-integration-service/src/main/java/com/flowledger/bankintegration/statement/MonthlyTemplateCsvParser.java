package com.flowledger.bankintegration.statement;

import org.springframework.stereotype.Component;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses FlowLedger's monthly bank transactions template: the bank's own "Transaction details"
 * report for one month, row by row as the bank prints it (newest or oldest first), with two columns
 * added by the user, Category and Other Description. All the bank statement's checks apply, and
 * every row must be in the chosen month.
 */
@Component
public class MonthlyTemplateCsvParser {

    public static final String FORMAT = "FLOWLEDGER_MONTHLY_CSV";
    public static final List<String> COLUMNS = TransactionDetailsCsvParser.COLUMNS_WITH_CATEGORIES;

    private final TransactionDetailsCsvParser statementParser;

    public MonthlyTemplateCsvParser(TransactionDetailsCsvParser statementParser) {
        this.statementParser = statementParser;
    }

    public ParsedStatement parse(byte[] content, YearMonth month) {
        ParsedStatement statement = statementParser.parseWithCategories(content, FORMAT);
        if (statement.hasErrors()) {
            return statement;
        }
        StatementErrors errors = new StatementErrors();
        for (StatementLine line : statement.lines()) {
            if (!YearMonth.from(line.transactionDate()).equals(month)) {
                errors.add(line.lineNumber(), TransactionDetailsCsvParser.DATE, line.transactionDate() + " is not in "
                        + monthName(month) + ". Upload one month at a time.");
            }
        }
        return errors.any() ? ParsedStatement.failed(FORMAT, new ArrayList<>(errors.list)) : statement;
    }

    public static String monthName(YearMonth month) {
        Month m = month.getMonth();
        return m.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
    }
}
