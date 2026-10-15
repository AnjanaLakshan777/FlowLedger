package com.flowledger.cashmanagement.salesimport;

import com.flowledger.cashmanagement.salesimport.SalesImportCsvParser.ParsedFile;
import com.flowledger.cashmanagement.salesimport.SalesImportCsvParser.ParsedRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SalesImportCsvParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final String HEADER = "date,cash_sales,card_payments\n";

    @Test
    void shouldReadValidRows() {
        ParsedFile file = parse(HEADER + "2025-01-01,416610.00,509190.5\n2025-01-02,0,12\n");

        assertThat(file.fileError()).isNull();
        assertThat(file.rows()).hasSize(2).allMatch(ParsedRow::valid);
        ParsedRow first = file.rows().getFirst();
        assertThat(first.rowNumber()).isEqualTo(2);
        assertThat(first.saleDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(first.cashSales()).isEqualTo(new BigDecimal("416610.00"));
        assertThat(first.cardSales()).isEqualTo(new BigDecimal("509190.50"));
    }

    @Test
    void shouldAcceptByteOrderMarkWindowsLineEndingsQuotesAndBlankLines() {
        ParsedFile file = parse("﻿date , cash_sales,card_payments\r\n\r\n\"2025-01-01\",\"100\", 200 \r\n\r\n");

        assertThat(file.fileError()).isNull();
        assertThat(file.rows()).singleElement().satisfies(row -> {
            assertThat(row.valid()).isTrue();
            assertThat(row.rowNumber()).isEqualTo(3);
            assertThat(row.cardSales()).isEqualTo(new BigDecimal("200.00"));
        });
    }

    @Test
    void shouldTreatZeroAsAValueAndBlankAsMissing() {
        ParsedFile file = parse(HEADER + "2025-01-01,0,0\n2025-01-02,,0\n2025-01-03,0,\n");

        assertThat(file.rows().get(0).valid()).isTrue();
        assertThat(file.rows().get(0).cashSales()).isEqualTo(new BigDecimal("0.00"));
        assertThat(file.rows().get(1).errors()).singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo("MISSING"))
                .satisfies(error -> assertThat(error.field()).isEqualTo("cash_sales"));
        assertThat(file.rows().get(2).errors()).extracting(SalesImportCsvParser.RowError::field)
                .containsExactly("card_payments");
    }

    @ParameterizedTest(name = "date {0}")
    @CsvSource({
            "2025-1-1, INVALID_FORMAT",
            "01/02/2025, INVALID_FORMAT",
            "2025-02-30, INVALID_FORMAT",
            "2025-13-01, INVALID_FORMAT",
            "20250101, INVALID_FORMAT",
            "2026-10-16, FUTURE_DATE"
    })
    void shouldRejectInvalidAndFutureDates(String date, String code) {
        ParsedRow row = parse(HEADER + date + ",10,10\n").rows().getFirst();

        assertThat(row.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("date");
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.message()).doesNotContain(date);
        });
    }

    @Test
    void shouldAcceptToday() {
        assertThat(parse(HEADER + "2026-10-15,10,10\n").rows().getFirst().valid()).isTrue();
    }

    @ParameterizedTest(name = "amount {0}")
    @CsvSource(delimiter = '|', value = {
            "-5|NEGATIVE",
            "-0.01|NEGATIVE",
            "\"1,000\"|INVALID_FORMAT",
            "Rs 100|INVALID_FORMAT",
            "1e3|INVALID_FORMAT",
            "10.123|INVALID_FORMAT",
            "123456789012345678|INVALID_FORMAT",
            "abc|INVALID_FORMAT"
    })
    void shouldRejectInvalidAmounts(String amount, String code) {
        ParsedRow row = parse(HEADER + "2025-01-01," + amount + ",0\n").rows().getFirst();

        assertThat(row.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("cash_sales");
            assertThat(error.code()).isEqualTo(code);
        });
        assertThat(row.cashSales()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-01-01,10\n", "2025-01-01,10,10,10\n"})
    void shouldRejectRowsWithTheWrongNumberOfValues(String line) {
        ParsedRow row = parse(HEADER + line).rows().getFirst();

        assertThat(row.errors()).singleElement().satisfies(error -> assertThat(error.code()).isEqualTo("COLUMN_COUNT"));
    }

    @Test
    void shouldKeepAShortenedCopyOfTheCells() {
        ParsedRow row = parse(HEADER + "x".repeat(100) + ",1,2\n").rows().getFirst();

        assertThat(row.raw().get("date")).hasSize(SalesImportCsvParser.MAX_RAW_LENGTH + 1).endsWith("…");
        assertThat(row.raw()).containsEntry("cash_sales", "1").containsEntry("card_payments", "2");
    }

    // Line breaks are written as "/" in the test data and turned into newlines.
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "wrong header|Date,Cash,Card/2025-01-01,1,1/|The first row must be exactly",
            "missing column|date,cash_sales/2025-01-01,1/|The first row must be exactly",
            "extra column|date,cash_sales,card_payments,notes/|The first row must be exactly",
            "header only|date,cash_sales,card_payments//|no data rows",
            "unclosed quote|date,cash_sales,card_payments/\"2025-01-01,1,1/|unclosed quote"
    })
    void shouldRejectFilesThatCannotBeRead(String description, String content, String error) {
        ParsedFile file = parse(content.replace('/', '\n'));

        assertThat(file.fileError()).contains(error);
        assertThat(file.rows()).isEmpty();
    }

    @Test
    void shouldRejectAnEmptyFile() {
        assertThat(parse("  \n\n").fileError()).isEqualTo("The file is empty.");
    }

    @Test
    void shouldRejectTextThatIsNotUtf8() {
        byte[] latin1 = (HEADER + "2025-01-01,1,1 café\n").getBytes(StandardCharsets.ISO_8859_1);

        assertThat(SalesImportCsvParser.parse(latin1, TODAY).fileError()).contains("UTF-8");
    }

    @Test
    void shouldRejectTooManyRows() {
        StringBuilder content = new StringBuilder(HEADER);
        LocalDate date = LocalDate.of(2000, 1, 1);
        for (int i = 0; i <= SalesImportCsvParser.MAX_DATA_ROWS; i++) {
            content.append(date.plusDays(i)).append(",1,1\n");
        }

        assertThat(parse(content.toString()).fileError()).contains("more than 5,000 data rows");
    }

    @Test
    void shouldAcceptTheMaximumNumberOfRows() {
        StringBuilder content = new StringBuilder(HEADER);
        LocalDate date = LocalDate.of(2000, 1, 1);
        for (int i = 0; i < SalesImportCsvParser.MAX_DATA_ROWS; i++) {
            content.append(date.plusDays(i)).append(",1,1\n");
        }

        assertThat(parse(content.toString()).rows()).hasSize(SalesImportCsvParser.MAX_DATA_ROWS);
    }

    private static ParsedFile parse(String content) {
        return SalesImportCsvParser.parse(content.getBytes(StandardCharsets.UTF_8), TODAY);
    }
}
