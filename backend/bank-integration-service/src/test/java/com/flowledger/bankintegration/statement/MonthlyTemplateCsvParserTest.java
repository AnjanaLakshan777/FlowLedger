package com.flowledger.bankintegration.statement;

import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.entity.TransactionDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyTemplateCsvParserTest {

    private static final String HEADER =
            "Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID,Category,Other Description\n";
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private final MonthlyTemplateCsvParser parser = new MonthlyTemplateCsvParser(new TransactionDetailsCsvParser());

    @Test
    void shouldReadTheBanksRowsNewestFirstWithTheirCategories() {
        // As the bank prints it: a title row, newest first, thousands separators, zero-filled cheque numbers.
        ParsedStatement statement = parse("TRANSACTION DETAILS,,,,,,,,,\n" + HEADER
                + "9/30/2026,INTEREST WITHHELD,Debit,,1.43,\"8,373.55\",000000000,631,Other,Withholding tax\n"
                + "9/30/2026,INTEREST PAY SYS-GEN,Credit,14.32,,\"8,374.98\",000000000,631,OTHER,\n"
                + "9/25/2026,CHQ PAID,Debit,,\"1,600.00\",\"8,360.66\",000605001,777,Cheque payment,\n"
                + "9/24/2026,MERCHANT SETTLEMENT,Credit,\"2,250.00\",,\"9,960.66\",000000000,777,card settlement,\n"
                + "9/24/2026,CASH DEPOSIT,Credit,300,,\"7,710.66\",0,631,CASH_SALES_DEPOSIT,\n");

        assertThat(statement.errors()).isEmpty();
        assertThat(statement.format()).isEqualTo(MonthlyTemplateCsvParser.FORMAT);
        assertThat(statement.newestFirst()).isTrue();
        assertThat(statement.transactionsOldestFirst()).extracting(StatementLine::category).containsExactly(
                TransactionCategory.CASH_SALES_DEPOSIT, TransactionCategory.CARD_SALES_SETTLEMENT,
                TransactionCategory.CHEQUE_PAYMENT, TransactionCategory.OTHER, TransactionCategory.OTHER);
        StatementLine cheque = statement.lines().get(2);
        assertThat(cheque.direction()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(cheque.chequeNumber()).isEqualTo("000605001");
        assertThat(cheque.branchCode()).isEqualTo("777");
        // Other Description is optional and kept only for OTHER.
        assertThat(statement.lines().get(0).otherDescription()).isEqualTo("Withholding tax");
        assertThat(statement.lines().get(1).otherDescription()).isNull();
        assertThat(statement.latestLine().runningBalance()).isEqualByComparingTo("8373.55");
        assertThat(statement.transactionsOldestFirst().getFirst().transactionDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "outside the month | 10/1/2026,Deposit,Credit,10.00,,10.00,0,631,CASH_SALES_DEPOSIT, | Date | is not in September 2026",
            "missing category  | 9/1/2026,Deposit,Credit,10.00,,10.00,0,631,,                   | Category | Category is missing",
            "unknown category  | 9/1/2026,Deposit,Credit,10.00,,10.00,0,631,Sales,              | Category | is not a category",
            "receipt as debit  | 9/1/2026,Deposit,Debit,,10.00,10.00,0,631,CASH_SALES_DEPOSIT, | Category | must be Credit",
            "cheque as credit  | 9/1/2026,Cheque,Credit,10.00,,10.00,123,631,CHEQUE_PAYMENT,   | Category | must be Debit",
            "cheque no number  | 9/1/2026,Cheque,Debit,,10.00,10.00,000000000,631,CHEQUE_PAYMENT, | Cheque Number | needs its Cheque Number",
            "cheque blank      | 9/1/2026,Cheque,Debit,,10.00,10.00,,631,CHEQUE_PAYMENT,      | Cheque Number | needs its Cheque Number",
    })
    void shouldRejectInvalidRows(String name, String row, String field, String message) {
        ParsedStatement statement = parse(HEADER + row.trim() + "\n");

        assertThat(statement.hasErrors()).isTrue();
        assertThat(statement.errors()).anySatisfy(error -> {
            assertThat(error.field()).isEqualTo(field);
            assertThat(error.message()).contains(message);
        });
    }

    @Test
    void shouldRequireTheCategoryColumns() {
        // The bank's file as downloaded, without the two columns the user adds.
        ParsedStatement statement = parse("Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID\n"
                + "9/1/2026,Deposit,Credit,10.00,,10.00,0,631\n");

        assertThat(statement.errors()).extracting(StatementError::message)
                .contains("Column \"Category\" is missing.", "Column \"Other Description\" is missing.");
    }

    @Test
    void shouldRejectABrokenBalanceChain() {
        ParsedStatement statement = parse(HEADER
                + "9/1/2026,Deposit,Credit,100.00,,1100.00,0,631,CASH_SALES_DEPOSIT,\n"
                + "9/2/2026,Deposit,Credit,100.00,,1300.00,0,631,CASH_SALES_DEPOSIT,\n");

        assertThat(statement.errors()).singleElement().satisfies(error -> {
            assertThat(error.line()).isEqualTo(3);
            assertThat(error.field()).isEqualTo("Running Balance");
        });
    }

    private ParsedStatement parse(String content) {
        return parser.parse(content.getBytes(StandardCharsets.UTF_8), SEPTEMBER);
    }
}
