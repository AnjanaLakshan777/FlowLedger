package com.flowledger.bankintegration.statement;

import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.entity.TransactionDirection;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the "TRANSACTION DETAILS" CSV statement layout (see the sample in the test resources).
 * Other banks' layouts need their own parser producing the same {@link ParsedStatement}.
 * <p>
 * Every row must be valid, or the whole statement is rejected with all errors found. Rows must be
 * in date order (newest or oldest first) and each running balance must follow from the previous
 * balance and the row's amount, so the latest balance can be trusted and no rows are missing.
 */
@Component
public class TransactionDetailsCsvParser {

    public static final String FORMAT = "TRANSACTION_DETAILS_CSV";

    static final String DATE = "Date";
    static final String DESCRIPTION = "Description";
    static final String TRANSACTION_TYPE = "Transaction Type";
    static final String CREDIT_AMOUNT = "Credit Amount";
    static final String DEBIT_AMOUNT = "Debit Amount";
    static final String RUNNING_BALANCE = "Running Balance";
    static final String CHEQUE_NUMBER = "Cheque Number";
    static final String BRANCH_ID = "Branch ID";
    // Added by the user to the bank's own columns in FlowLedger's monthly template.
    static final String CATEGORY = "Category";
    static final String OTHER_DESCRIPTION = "Other Description";
    private static final List<String> COLUMNS = List.of(
            DATE, DESCRIPTION, TRANSACTION_TYPE, CREDIT_AMOUNT, DEBIT_AMOUNT, RUNNING_BALANCE, CHEQUE_NUMBER, BRANCH_ID);
    public static final List<String> COLUMNS_WITH_CATEGORIES = List.of(
            DATE, DESCRIPTION, TRANSACTION_TYPE, CREDIT_AMOUNT, DEBIT_AMOUNT, RUNNING_BALANCE, CHEQUE_NUMBER, BRANCH_ID,
            CATEGORY, OTHER_DESCRIPTION);
    private static final int MAX_OTHER_DESCRIPTION_LENGTH = 120;

    // The header follows at most a few title rows such as "TRANSACTION DETAILS".
    private static final int MAX_ROWS_BEFORE_HEADER = 10;
    private static final int MAX_DATA_ROWS = 20_000;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    // Month first, as in "9/30/2026". Strict, so 2/30 or 13/1 are rejected rather than adjusted.
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    // "10,150.00", "105" or "0": correctly grouped thousands, at most two decimal places.
    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d{1,2})?");
    private static final Pattern CHEQUE = Pattern.compile("\\d{1,40}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9-]{1,20}");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

    public ParsedStatement parse(byte[] content) {
        return parse(content, false, FORMAT);
    }

    /**
     * The bank's columns plus Category (required) and Other Description (optional), as in
     * FlowLedger's monthly template: the user pastes the bank's rows and gives each its category.
     */
    public ParsedStatement parseWithCategories(byte[] content, String format) {
        return parse(content, true, format);
    }

    private ParsedStatement parse(byte[] content, boolean withCategories, String format) {
        List<CSVRecord> records;
        try {
            records = readRecords(content);
        } catch (CharacterCodingException e) {
            return fileError(format, "The file is not valid UTF-8 text.");
        } catch (IOException | UncheckedIOException e) {
            return fileError(format, "The file is not valid CSV, for example because of an unclosed quote.");
        }

        List<String> expectedColumns = withCategories ? COLUMNS_WITH_CATEGORIES : COLUMNS;
        StatementErrors errors = new StatementErrors();
        int headerIndex = findHeader(records, expectedColumns, errors);
        if (headerIndex < 0) {
            return ParsedStatement.failed(format, errors.list);
        }
        Map<String, Integer> columns = columnIndexes(records.get(headerIndex), expectedColumns, errors);
        if (errors.any()) {
            return ParsedStatement.failed(format, errors.list);
        }

        List<StatementLine> lines = new ArrayList<>();
        int headerSize = records.get(headerIndex).size();
        for (CSVRecord record : records.subList(headerIndex + 1, records.size())) {
            if (isBlank(record)) {
                continue;
            }
            if (lines.size() == MAX_DATA_ROWS) {
                errors.add(null, null, "The statement has more than " + MAX_DATA_ROWS + " rows.");
                break;
            }
            StatementLine line = parseLine(record, headerSize, columns, withCategories, errors);
            if (line != null) {
                lines.add(line);
            }
        }
        if (errors.any()) {
            return ParsedStatement.failed(format, errors.list);
        }
        if (lines.isEmpty()) {
            return fileError(format, "The statement contains no transactions.");
        }

        boolean newestFirst = StatementOrder.check(lines, DATE, RUNNING_BALANCE, errors);
        return errors.any()
                ? ParsedStatement.failed(format, errors.list)
                : new ParsedStatement(format, List.copyOf(lines), newestFirst, List.of());
    }

    // Record numbers equal file line numbers: empty lines are kept as records and line breaks
    // inside quoted fields are rejected below.
    private static List<CSVRecord> readRecords(byte[] content) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        CSVFormat format = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get();
        try (CSVParser parser = CSVParser.parse(text, format)) {
            return parser.getRecords();
        }
    }

    private static int findHeader(List<CSVRecord> records, List<String> expectedColumns, StatementErrors errors) {
        int limit = Math.min(records.size(), MAX_ROWS_BEFORE_HEADER + 1);
        for (int i = 0; i < limit; i++) {
            CSVRecord record = records.get(i);
            if (normalized(record.get(0)).equals(normalized(DATE))) {
                return i;
            }
            // Title rows are skipped, but a row that already looks like a transaction means the
            // file does not have the expected layout.
            if (parseDate(record.get(0).trim()) != null) {
                errors.add(lineOf(record), null, "A transaction row appears before the header row.");
                return -1;
            }
        }
        errors.add(null, null, "The file does not have the expected header row: " + String.join(", ", expectedColumns) + ".");
        return -1;
    }

    private static Map<String, Integer> columnIndexes(CSVRecord header, List<String> expectedColumns, StatementErrors errors) {
        Map<String, String> expected = new HashMap<>();
        expectedColumns.forEach(column -> expected.put(normalized(column), column));

        Map<String, Integer> indexes = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = normalized(header.get(i));
            if (name.isEmpty()) {
                continue;
            }
            String column = expected.get(name);
            if (column == null) {
                errors.add(lineOf(header), null, "Unsupported column \"" + header.get(i).trim() + "\".");
            } else if (indexes.putIfAbsent(column, i) != null) {
                errors.add(lineOf(header), null, "Column \"" + column + "\" appears more than once.");
            }
        }
        expectedColumns.stream()
                .filter(column -> !indexes.containsKey(column))
                .forEach(column -> errors.add(lineOf(header), null, "Column \"" + column + "\" is missing."));
        return indexes;
    }

    private static StatementLine parseLine(CSVRecord record, int headerSize, Map<String, Integer> columns,
                                           boolean withCategories, StatementErrors errors) {
        int line = lineOf(record);
        if (record.size() != headerSize) {
            errors.add(line, null, "Expected " + headerSize + " columns but found " + record.size() + ".");
            return null;
        }
        for (String value : record) {
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                errors.add(line, null, "Line breaks inside a field are not supported.");
                return null;
            }
        }
        int errorsBefore = errors.list.size();

        String dateText = field(record, columns, DATE);
        LocalDate date = parseDate(dateText);
        if (date == null) {
            errors.add(line, DATE, "\"" + dateText + "\" is not a valid date in the format M/D/YYYY.");
        }

        String description = field(record, columns, DESCRIPTION);
        if (description.isEmpty()) {
            errors.add(line, DESCRIPTION, "Description is empty.");
        } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
            errors.add(line, DESCRIPTION, "Description is longer than " + MAX_DESCRIPTION_LENGTH + " characters.");
        } else if (CONTROL_CHARACTERS.matcher(description).find()) {
            errors.add(line, DESCRIPTION, "Description contains control characters.");
        }

        TransactionDirection direction = switch (field(record, columns, TRANSACTION_TYPE).toLowerCase(Locale.ROOT)) {
            case "credit" -> TransactionDirection.CREDIT;
            case "debit" -> TransactionDirection.DEBIT;
            default -> {
                errors.add(line, TRANSACTION_TYPE, "Transaction Type must be Credit or Debit.");
                yield null;
            }
        };
        int errorsBeforeAmounts = errors.list.size();
        BigDecimal credit = parseOptionalAmount(record, columns, CREDIT_AMOUNT, line, errors);
        BigDecimal debit = parseOptionalAmount(record, columns, DEBIT_AMOUNT, line, errors);
        // An unreadable amount is already reported; checking it against the type would only add noise.
        BigDecimal amount = direction == null || errors.list.size() > errorsBeforeAmounts
                ? null
                : amountFor(direction, credit, debit, line, errors);

        String balanceText = field(record, columns, RUNNING_BALANCE);
        BigDecimal runningBalance = parseSignedAmount(balanceText);
        if (runningBalance == null) {
            errors.add(line, RUNNING_BALANCE, balanceText.isEmpty()
                    ? "Running Balance is missing."
                    : "\"" + balanceText + "\" is not a valid amount.");
        }

        String chequeText = field(record, columns, CHEQUE_NUMBER);
        if (!chequeText.isEmpty() && !CHEQUE.matcher(chequeText).matches()) {
            errors.add(line, CHEQUE_NUMBER, "Cheque Number must contain only digits.");
        }
        String branchText = field(record, columns, BRANCH_ID);
        if (!branchText.isEmpty() && !BRANCH.matcher(branchText).matches()) {
            errors.add(line, BRANCH_ID, "Branch ID must be up to 20 letters, digits or hyphens.");
        }

        TransactionCategory category = TransactionCategory.UNCLASSIFIED;
        String otherDescription = null;
        if (withCategories) {
            category = parseCategory(field(record, columns, CATEGORY), direction, line, errors);
            // A blank or all-zero number means no cheque; an invalid one is already reported above.
            boolean noCheque = chequeText.isEmpty() || (CHEQUE.matcher(chequeText).matches() && chequeNumber(chequeText) == null);
            if (category == TransactionCategory.CHEQUE_PAYMENT && noCheque) {
                errors.add(line, CHEQUE_NUMBER, "A cheque payment needs its Cheque Number, so the cheque can be shown as cleared.");
            }
            String otherText = field(record, columns, OTHER_DESCRIPTION);
            if (otherText.length() > MAX_OTHER_DESCRIPTION_LENGTH) {
                errors.add(line, OTHER_DESCRIPTION, "Other Description is longer than " + MAX_OTHER_DESCRIPTION_LENGTH + " characters.");
            } else if (CONTROL_CHARACTERS.matcher(otherText).find()) {
                errors.add(line, OTHER_DESCRIPTION, "Other Description contains control characters.");
            } else if (category == TransactionCategory.OTHER && !otherText.isEmpty()) {
                otherDescription = otherText;
            }
        }

        if (errors.list.size() > errorsBefore) {
            return null;
        }
        return new StatementLine(line, date, direction, amount, description, runningBalance,
                chequeNumber(chequeText), branchText.isEmpty() ? null : branchText, category, otherDescription);
    }

    /**
     * The category's name or its label, in any case: CASH_SALES_DEPOSIT or "Cash sales deposit",
     * CARD_SALES_SETTLEMENT or "Card settlement", CHEQUE_PAYMENT or "Cheque payment", OTHER. It must fit
     * the direction: sales receipts are money in and cheque payments money out.
     */
    private static TransactionCategory parseCategory(String text, TransactionDirection direction, int line,
                                                     StatementErrors errors) {
        String key = text.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        TransactionCategory category = switch (key) {
            case "CASH_SALES_DEPOSIT", "CASH_DEPOSIT" -> TransactionCategory.CASH_SALES_DEPOSIT;
            case "CARD_SALES_SETTLEMENT", "CARD_SETTLEMENT" -> TransactionCategory.CARD_SALES_SETTLEMENT;
            case "CHEQUE_PAYMENT" -> TransactionCategory.CHEQUE_PAYMENT;
            case "OTHER" -> TransactionCategory.OTHER;
            default -> null;
        };
        if (category == null) {
            errors.add(line, CATEGORY, text.isEmpty() ? "Category is missing."
                    : "\"" + text + "\" is not a category. Use CASH_SALES_DEPOSIT, CARD_SALES_SETTLEMENT, CHEQUE_PAYMENT or OTHER.");
            return TransactionCategory.UNCLASSIFIED;
        }
        if (direction != null && !category.fits(direction)) {
            errors.add(line, CATEGORY, category == TransactionCategory.CHEQUE_PAYMENT
                    ? "A cheque payment is money out, so Transaction Type must be Debit."
                    : "A sales receipt is money in, so Transaction Type must be Credit.");
        }
        return category;
    }

    // The amount must be in the column matching the transaction type. A blank or zero value in
    // the other column is fine; a non-zero one contradicts the type.
    private static BigDecimal amountFor(TransactionDirection direction, BigDecimal credit, BigDecimal debit,
                                        int line, StatementErrors errors) {
        BigDecimal amount = direction == TransactionDirection.CREDIT ? credit : debit;
        BigDecimal other = direction == TransactionDirection.CREDIT ? debit : credit;
        String amountColumn = direction == TransactionDirection.CREDIT ? CREDIT_AMOUNT : DEBIT_AMOUNT;
        String otherColumn = direction == TransactionDirection.CREDIT ? DEBIT_AMOUNT : CREDIT_AMOUNT;

        if (other != null && other.signum() != 0) {
            errors.add(line, otherColumn, "A " + direction.name().toLowerCase(Locale.ROOT)
                    + " transaction must not have a " + otherColumn + ".");
            return null;
        }
        if (amount == null) {
            errors.add(line, amountColumn, "A " + direction.name().toLowerCase(Locale.ROOT)
                    + " transaction needs a " + amountColumn + ".");
        }
        return amount;
    }

    private static BigDecimal parseOptionalAmount(CSVRecord record, Map<String, Integer> columns, String column,
                                                  int line, StatementErrors errors) {
        String text = field(record, columns, column);
        if (text.isEmpty()) {
            return null;
        }
        BigDecimal amount = parseAmount(text);
        if (amount == null) {
            errors.add(line, column, "\"" + text + "\" is not a valid amount.");
        }
        return amount;
    }

    private static BigDecimal parseAmount(String text) {
        return AMOUNT.matcher(text).matches() ? new BigDecimal(text.replace(",", "")).setScale(2) : null;
    }

    // Balances may be negative when the bank allows an overdraft.
    private static BigDecimal parseSignedAmount(String text) {
        if (text.startsWith("-")) {
            BigDecimal amount = parseAmount(text.substring(1));
            return amount == null ? null : amount.negate();
        }
        return parseAmount(text);
    }

    // The bank fills the column with zeros when there is no cheque; real numbers keep leading zeros.
    private static String chequeNumber(String text) {
        return text.isEmpty() || text.chars().allMatch(c -> c == '0') ? null : text;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String field(CSVRecord record, Map<String, Integer> columns, String column) {
        return record.get(columns.get(column)).trim();
    }

    private static boolean isBlank(CSVRecord record) {
        for (String value : record) {
            if (!value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String normalized(String header) {
        return header.trim().toLowerCase(Locale.ROOT);
    }

    private static int lineOf(CSVRecord record) {
        return Math.toIntExact(record.getRecordNumber());
    }

    private static ParsedStatement fileError(String format, String message) {
        return ParsedStatement.failed(format, List.of(new StatementError(null, null, message)));
    }

}
