package com.flowledger.forecast.holiday;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads one year's holiday list, copied from the Central Bank of Sri Lanka's "Bank Holidays" page:
 * {@code date,name,codes} with an ISO date, the holiday's name, and its codes (B bank, P public,
 * M mercantile, in any order and punctuation, such as "B. P. M."). Every row must be valid and in the
 * chosen year, or the whole file is refused with all problems found.
 */
public final class HolidayCsvParser {

    public record Row(LocalDate date, String name, boolean bank, boolean publicHoliday, boolean mercantile,
                      boolean poya) {
    }

    public record Result(List<Row> rows, List<String> errors) {
    }

    static final int MAX_ROWS = 200;
    private static final int MAX_NAME_LENGTH = 200;
    private static final Pattern CODES = Pattern.compile("[BPMbpm][\\s.,/]*([BPMbpm][\\s.,/]*)*");

    private HolidayCsvParser() {
    }

    public static Result parse(byte[] content, int year) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return failed("The file is not valid UTF-8 text.");
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().toList();
        int headerIndex = 0;
        while (headerIndex < lines.size() && lines.get(headerIndex).isBlank()) {
            headerIndex++;
        }
        if (headerIndex == lines.size()) {
            return failed("The file is empty.");
        }
        List<String> header = split(lines.get(headerIndex));
        if (header == null || !header.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList()
                .equals(List.of("date", "name", "codes"))) {
            return failed("The first row must be the header: date,name,codes.");
        }

        List<Row> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lineNumber = i + 1;
            if (rows.size() + errors.size() >= MAX_ROWS) {
                errors.add("The file has more than " + MAX_ROWS + " rows.");
                break;
            }
            List<String> fields = split(line);
            if (fields == null || fields.size() != 3) {
                errors.add("Line " + lineNumber + ": expected 3 columns (date,name,codes).");
                continue;
            }
            String dateText = fields.get(0).trim();
            String name = fields.get(1).trim();
            String codes = fields.get(2).trim();
            int before = errors.size();

            LocalDate date = null;
            try {
                date = LocalDate.parse(dateText);
                if (date.getYear() != year) {
                    errors.add("Line " + lineNumber + ": " + dateText + " is not in " + year + ".");
                }
            } catch (DateTimeParseException e) {
                errors.add("Line " + lineNumber + ": \"" + dateText + "\" is not a date in the format YYYY-MM-DD.");
            }
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
                errors.add("Line " + lineNumber + ": name must be 1 to " + MAX_NAME_LENGTH + " characters.");
            }
            if (!CODES.matcher(codes).matches()) {
                errors.add("Line " + lineNumber + ": codes must be B (bank), P (public) and/or M (mercantile), for example B. P. M.");
            }
            if (errors.size() > before) {
                continue;
            }
            if (!seen.add(date + "|" + name.toLowerCase(Locale.ROOT))) {
                errors.add("Line " + lineNumber + ": " + name + " on " + date + " is listed twice.");
                continue;
            }
            String upper = codes.toUpperCase(Locale.ROOT);
            rows.add(new Row(date, name, upper.contains("B"), upper.contains("P"), upper.contains("M"), isPoya(name)));
        }
        if (errors.isEmpty() && rows.isEmpty()) {
            return failed("The file lists no holidays.");
        }
        return errors.isEmpty() ? new Result(List.copyOf(rows), List.of()) : new Result(List.of(), List.copyOf(errors));
    }

    // "Vesak Full Moon Poya Day" is a Poya day; "Day following Vesak Full Moon Poya Day" is not.
    static boolean isPoya(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("full moon poya day") && !lower.startsWith("day following");
    }

    // Comma-separated fields; a field in double quotes may hold commas, and "" is a quote. Null if a quote is left open.
    private static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        fields.add(field.toString());
        return fields;
    }

    private static Result failed(String error) {
        return new Result(List.of(), List.of(error));
    }
}
