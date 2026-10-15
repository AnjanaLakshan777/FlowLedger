package com.flowledger.bankintegration.statement;

import com.flowledger.bankintegration.entity.TransactionDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionDetailsCsvParserTest {

    private static final String HEADER =
            "Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID\r\n";

    private final TransactionDetailsCsvParser parser = new TransactionDetailsCsvParser();

    @Test
    void shouldParseTheUploadedSampleStatement() throws IOException {
        ParsedStatement statement = parser.parse(sample());

        assertThat(statement.errors()).isEmpty();
        assertThat(statement.format()).isEqualTo("TRANSACTION_DETAILS_CSV");
        assertThat(statement.lines()).hasSize(59);
        assertThat(statement.newestFirst()).isTrue();
        assertThat(statement.zeroAmountCount()).isEqualTo(4);
        assertThat(statement.transactionsOldestFirst()).hasSize(55);
        assertThat(statement.periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(statement.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));

        // The file lists the newest transaction first; its balance is the closing balance.
        StatementLine latest = statement.latestLine();
        assertThat(latest.lineNumber()).isEqualTo(3);
        assertThat(latest.description()).isEqualTo("INTEREST WITHHELD");
        assertThat(latest.runningBalance()).isEqualByComparingTo("8373.55");

        StatementLine first = statement.lines().getFirst();
        assertThat(first.transactionDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(first.direction()).isEqualTo(TransactionDirection.DEBIT);
        assertThat(first.amount()).isEqualByComparingTo("1.43");
        assertThat(first.chequeNumber()).isNull(); // 000000000 means "no cheque"
        assertThat(first.branchCode()).isEqualTo("631");

        StatementLine credit = statement.lines().get(1);
        assertThat(credit.direction()).isEqualTo(TransactionDirection.CREDIT);
        assertThat(credit.amount()).isEqualByComparingTo("14.32");
        assertThat(statement.lines().get(3).amount()).isEqualByComparingTo("1060.41"); // "1,060.41"
        assertThat(statement.lines().get(4).amount()).isEqualByComparingTo("105");     // no decimals
    }

    @Test
    void shouldKeepLegitimatelyRepeatedSampleTransactionsDistinct() throws IOException {
        ParsedStatement statement = parser.parse(sample());

        // 9/7/2026 has three identical HUTCHISON TELECOM 100 debits and two identical ATM rows.
        List<StatementLine> repeated = statement.lines().stream()
                .filter(line -> line.transactionDate().equals(LocalDate.of(2026, 9, 7)))
                .filter(line -> line.description().equals("ECOMMERCE HUTCHISON TELECOM LA"))
                .toList();
        assertThat(repeated).hasSize(3);

        List<String> fingerprints = TransactionFingerprints.of(statement.transactionsOldestFirst());
        assertThat(new HashSet<>(fingerprints)).hasSize(55);
        assertThat(TransactionFingerprints.of(parser.parse(sample()).transactionsOldestFirst())).isEqualTo(fingerprints);
    }

    @Test
    void shouldSkipSeveralTitleRowsAndAcceptByteOrderMark() {
        String csv = "﻿BANK OF EXAMPLE,,,,,,,\r\nTRANSACTION DETAILS,,,,,,,\r\n,,,,,,,\r\n" + HEADER
                + "9/30/2026,SALARY,Credit,\"10,150.00\",,\"10,150.00\",000000000,631\r\n";

        ParsedStatement statement = parse(csv);

        assertThat(statement.errors()).isEmpty();
        assertThat(statement.lines()).singleElement()
                .satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("10150.00"));
    }

    @Test
    void shouldReadOldestFirstStatementAndUseItsLastRowAsLatest() {
        ParsedStatement statement = parse(HEADER
                + "9/1/2026,OPENING DEPOSIT,Credit,500,,500.00,,1\r\n"
                + "9/2/2026,ATM,Debit,,100,400.00,,1\r\n");

        assertThat(statement.errors()).isEmpty();
        assertThat(statement.newestFirst()).isFalse();
        assertThat(statement.latestLine().runningBalance()).isEqualByComparingTo("400.00");
    }

    @Test
    void shouldHandleQuotedFieldsWithCommasAndQuotes() {
        ParsedStatement statement = parse(HEADER
                + "9/30/2026,\"PAYMENT, \"\"URGENT\"\" REF 12\",Debit,,\"1,000.00\",\"-1,000.00\",000123,HO-1\r\n");

        assertThat(statement.errors()).isEmpty();
        StatementLine line = statement.lines().getFirst();
        assertThat(line.description()).isEqualTo("PAYMENT, \"URGENT\" REF 12");
        assertThat(line.runningBalance()).isEqualByComparingTo("-1000.00"); // overdraft
        assertThat(line.chequeNumber()).isEqualTo("000123");                // leading zeros kept
        assertThat(line.branchCode()).isEqualTo("HO-1");
    }

    @Test
    void shouldAcceptZeroInTheOtherAmountColumnAndSkipZeroAmountRows() {
        ParsedStatement statement = parse(HEADER
                + "9/30/2026,CARD CHECK,Debit,,0,500.00,,1\r\n"
                + "9/29/2026,DEPOSIT,Credit,500.00,0.00,500.00,,1\r\n");

        assertThat(statement.errors()).isEmpty();
        assertThat(statement.zeroAmountCount()).isEqualTo(1);
        assertThat(statement.transactionsOldestFirst()).singleElement()
                .satisfies(line -> assertThat(line.description()).isEqualTo("DEPOSIT"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "invalid date                   | 13/45/2026,X,Debit,,5,95.00,,1                | Date",
            "ISO date                       | 2026-09-30,X,Debit,,5,95.00,,1                | Date",
            "impossible date                | 2/30/2026,X,Debit,,5,95.00,,1                 | Date",
            "unknown type                   | 9/30/2026,X,Transfer,,5,95.00,,1              | Transaction Type",
            "credit type with debit amount  | 9/30/2026,X,Credit,,5,95.00,,1                | Debit Amount",
            "debit type without amount      | 9/30/2026,X,Debit,,,95.00,,1                  | Debit Amount",
            "both amounts                   | 9/30/2026,X,Debit,5,5,95.00,,1                | Credit Amount",
            "negative amount                | 9/30/2026,X,Debit,,-5,95.00,,1                | Debit Amount",
            "badly grouped amount           | 9/30/2026,X,Debit,,\"1,23.00\",95.00,,1       | Debit Amount",
            "three decimals                 | 9/30/2026,X,Debit,,5.001,95.00,,1             | Debit Amount",
            "text amount                    | 9/30/2026,X,Debit,,five,95.00,,1              | Debit Amount",
            "missing balance                | 9/30/2026,X,Debit,,5,,,1                      | Running Balance",
            "letters in cheque number       | 9/30/2026,X,Debit,,5,95.00,12A,1              | Cheque Number",
            "empty description              | 9/30/2026,,Debit,,5,95.00,,1                  | Description"
    })
    void shouldRejectInvalidRow(String rule, String row, String field) {
        ParsedStatement statement = parse(HEADER + row + "\r\n");

        assertThat(statement.hasErrors()).isTrue();
        assertThat(statement.lines()).isEmpty();
        assertThat(statement.errors()).anySatisfy(error -> {
            assertThat(error.line()).isEqualTo(2);
            assertThat(error.field()).isEqualTo(field);
        });
    }

    @Test
    void shouldRejectWholeStatementWhenOneRowIsInvalid() {
        ParsedStatement statement = parse(HEADER
                + "9/30/2026,GOOD,Debit,,5,95.00,,1\r\n"
                + "9/29/2026,BAD,Debit,,oops,100.00,,1\r\n");

        assertThat(statement.lines()).isEmpty();
        assertThat(statement.errors()).singleElement().satisfies(error -> assertThat(error.line()).isEqualTo(3));
    }

    @Test
    void shouldRejectRowWithWrongNumberOfColumns() {
        ParsedStatement statement = parse(HEADER + "9/30/2026,X,Debit,,5,95.00\r\n");

        assertThat(statement.errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).isEqualTo("Expected 8 columns but found 6."));
    }

    @Test
    void shouldRejectRunningBalanceThatDoesNotFollow() {
        // 9/29 -> 9/30: 100.00 - 5 should give 95.00, not 90.00 (a row is missing).
        ParsedStatement statement = parse(HEADER
                + "9/30/2026,ATM,Debit,,5,90.00,,1\r\n"
                + "9/29/2026,DEPOSIT,Credit,100,,100.00,,1\r\n");

        assertThat(statement.errors()).singleElement().satisfies(error -> {
            assertThat(error.line()).isEqualTo(2);
            assertThat(error.field()).isEqualTo("Running Balance");
        });
    }

    @Test
    void shouldRejectRowsOutOfDateOrder() {
        ParsedStatement statement = parse(HEADER
                + "9/30/2026,A,Debit,,5,95.00,,1\r\n"
                + "9/10/2026,B,Debit,,5,100.00,,1\r\n"
                + "9/20/2026,C,Debit,,5,105.00,,1\r\n");

        assertThat(statement.errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).isEqualTo("Transactions are not in date order."));
    }

    @Test
    void shouldRejectFileWithoutExpectedHeader() {
        assertThat(parse("Date,Narration,Amount\r\n9/30/2026,X,5\r\n").errors())
                .anySatisfy(error -> assertThat(error.message()).isEqualTo("Unsupported column \"Narration\"."));
        assertThat(parse("just some text\r\n").errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).startsWith("The file does not have the expected header row"));
    }

    @Test
    void shouldRejectTransactionRowBeforeHeader() {
        ParsedStatement statement = parse("9/30/2026,X,Debit,,5,95.00,,1\r\n" + HEADER);

        assertThat(statement.errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).isEqualTo("A transaction row appears before the header row."));
    }

    @Test
    void shouldRejectEmptyStatementAndBrokenFiles() {
        assertThat(parse(HEADER).errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).isEqualTo("The statement contains no transactions."));
        assertThat(parse(HEADER + "9/30/2026,\"UNCLOSED,Debit,,5,95.00,,1\r\n").errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).contains("not valid CSV"));
        assertThat(parser.parse(new byte[]{(byte) 0xC3, (byte) 0x28}).errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).isEqualTo("The file is not valid UTF-8 text."));
    }

    @Test
    void shouldGiveIdenticalRowsDistinctFingerprints() {
        // Money out and back in leaves the same balance: the first and third rows are identical.
        ParsedStatement statement = parse(HEADER
                + "9/30/2026,REFUND,Credit,100,,500.00,,1\r\n"
                + "9/30/2026,PAYMENT,Debit,,100,400.00,,1\r\n"
                + "9/30/2026,REFUND,Credit,100,,500.00,,1\r\n"
                + "9/29/2026,DEPOSIT,Credit,400,,400.00,,1\r\n");

        assertThat(statement.errors()).isEmpty();
        List<String> fingerprints = TransactionFingerprints.of(statement.transactionsOldestFirst());
        assertThat(new HashSet<>(fingerprints)).hasSize(4);
    }

    private ParsedStatement parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sample() throws IOException {
        try (InputStream in = TransactionDetailsCsvParserTest.class.getResourceAsStream("/statements/sample-bank-statement.csv")) {
            return in.readAllBytes();
        }
    }

    @Test
    void shouldReadAmountsAsExactDecimals() {
        ParsedStatement statement = parse(HEADER + "9/30/2026,X,Credit,\"1,234,567.89\",,\"1,234,567.89\",,1\r\n");

        assertThat(statement.lines().getFirst().amount()).isEqualTo(new BigDecimal("1234567.89"));
    }
}
