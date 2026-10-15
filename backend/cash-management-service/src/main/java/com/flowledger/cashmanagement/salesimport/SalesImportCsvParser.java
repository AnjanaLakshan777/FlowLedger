package com.flowledger.cashmanagement.salesimport;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a historical sales CSV file strictly: UTF-8, the header {@code date,cash_sales,card_payments},
 * one row per line, dates as YYYY-MM-DD, amounts as plain LKR numbers with at most two decimals.
 * A blank cell is missing, never zero. Problems that make the whole file unusable are returned as
 * a file error; problems of single rows are returned with the row. Error messages name the problem,
 * never the cell's value. Duplicate dates and dates already recorded are checked by the caller.
 */
public final class SalesImportCsvParser {

    public static final List<String> HEADER = List.of("date", "cash_sales", "card_payments");
    public static final int MAX_DATA_ROWS = 5_000;
    static final int MAX_RAW_LENGTH = 60;

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    // Fits NUMERIC(19, 2): up to 17 digits before the point.
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,17}(\\.[0-9]{1,2})?");
    private static final Pattern NEGATIVE_AMOUNT = Pattern.compile("-[0-9]{1,17}(\\.[0-9]{1,2})?");

    public record RowError(String field, String code, String message) {
    }

    /**
     * @param raw the cells as written (trimmed, shortened), kept only to show problems
     */
    public record ParsedRow(int rowNumber, Map<String, String> raw, LocalDate saleDate, BigDecimal cashSales,
                            BigDecimal cardSales, List<RowError> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Either a file error or the data rows, in file order. */
    public record ParsedFile(String fileError, List<ParsedRow> rows) {

        static ParsedFile failed(String error) {
            return new ParsedFile(error, List.of());
        }
    }

    private SalesImportCsvParser() {
    }

    public static ParsedFile parse(byte[] content, LocalDate today) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return ParsedFile.failed("The file is not UTF-8 text. Save it as CSV (UTF-8).");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }

        String[] lines = text.split("\r\n|\n|\r", -1);
        int headerIndex = firstNonBlank(lines, 0);
        if (headerIndex < 0) {
            return ParsedFile.failed("The file is empty.");
        }
        List<String> header = cells(lines[headerIndex]);
        if (header == null || !header.stream().map(String::strip).toList().equals(HEADER)) {
            return ParsedFile.failed("The first row must be exactly: date,cash_sales,card_payments");
        }

        List<ParsedRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            if (rows.size() == MAX_DATA_ROWS) {
                return ParsedFile.failed("The file has more than " + String.format("%,d", MAX_DATA_ROWS)
                        + " data rows. Split it into smaller files.");
            }
            List<String> cells = cells(lines[i]);
            if (cells == null) {
                return ParsedFile.failed("Row " + (i + 1) + " has an unclosed quote.");
            }
            rows.add(row(i + 1, cells, today));
        }
        if (rows.isEmpty()) {
            return ParsedFile.failed("The file has no data rows.");
        }
        return new ParsedFile(null, rows);
    }

    private static ParsedRow row(int rowNumber, List<String> cells, LocalDate today) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(cells.size(), HEADER.size()); i++) {
            raw.put(HEADER.get(i), shorten(cells.get(i).strip()));
        }
        List<RowError> errors = new ArrayList<>();
        if (cells.size() != HEADER.size()) {
            errors.add(new RowError("row", "COLUMN_COUNT",
                    "The row must have exactly 3 values: date, cash_sales, card_payments."));
            return new ParsedRow(rowNumber, raw, null, null, null, errors);
        }
        LocalDate saleDate = date(cells.get(0).strip(), today, errors);
        BigDecimal cash = amount("cash_sales", cells.get(1).strip(), errors);
        BigDecimal card = amount("card_payments", cells.get(2).strip(), errors);
        return new ParsedRow(rowNumber, raw, saleDate, cash, card, errors);
    }

    private static LocalDate date(String value, LocalDate today, List<RowError> errors) {
        if (value.isEmpty()) {
            errors.add(new RowError("date", "MISSING", "date is missing."));
            return null;
        }
        LocalDate date = null;
        if (DATE.matcher(value).matches()) {
            try {
                date = LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                // reported below
            }
        }
        if (date == null) {
            errors.add(new RowError("date", "INVALID_FORMAT", "date must be a real date written as YYYY-MM-DD."));
            return null;
        }
        if (date.isAfter(today)) {
            errors.add(new RowError("date", "FUTURE_DATE", "date must not be in the future."));
        }
        return date;
    }

    private static BigDecimal amount(String field, String value, List<RowError> errors) {
        if (value.isEmpty()) {
            errors.add(new RowError(field, "MISSING", field + " is missing. Write 0 for a day without such sales."));
            return null;
        }
        if (AMOUNT.matcher(value).matches()) {
            return new BigDecimal(value).setScale(2);
        }
        if (NEGATIVE_AMOUNT.matcher(value).matches()) {
            errors.add(new RowError(field, "NEGATIVE", field + " must not be negative."));
        } else {
            errors.add(new RowError(field, "INVALID_FORMAT", field
                    + " must be a number in LKR with at most two decimals, without currency symbols or separators."));
        }
        return null;
    }

    // Splits one line at commas, honouring double quotes ("" is a quote inside a quoted cell).
    // Returns null for an unclosed quote.
    static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        cells.add(cell.toString());
        return cells;
    }

    private static int firstNonBlank(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static String shorten(String value) {
        return value.length() <= MAX_RAW_LENGTH ? value : value.substring(0, MAX_RAW_LENGTH) + "…";
    }
}
