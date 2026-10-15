package com.flowledger.forecast.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A cheque's fields as payment-service sends them, in a cheque event payload or an internal scan
 * item, after strict validation. Unknown extra fields are ignored. Error messages name the field,
 * never its value.
 */
public record ChequeFields(
        UUID chequeId,
        UUID companyId,
        UUID bankAccountId,
        String chequeNumber,
        String category,
        BigDecimal amount,
        LocalDate issueDate,
        LocalDate expectedClearingDate,
        String status,
        int version
) {

    private static final Set<String> CATEGORIES = Set.of("SUPPLIER_PAYMENT", "SALARY", "TAX", "OTHER_EXPENSES");
    private static final Set<String> STATUSES = Set.of("ISSUED", "CANCELLED");
    private static final Pattern CHEQUE_NUMBER = Pattern.compile("[0-9]{1,40}");
    // Plain decimal as payment-service writes it, fitting NUMERIC(19, 2); no exponent or sign.
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,17}(\\.[0-9]{1,2})?");

    /**
     * @throws InvalidChequeEventException if a field is missing, of the wrong type, or invalid
     */
    public static ChequeFields from(Map<String, Object> payload) {
        UUID chequeId = uuid(text(payload, "chequeId"), "chequeId");
        UUID companyId = uuid(text(payload, "companyId"), "companyId");
        UUID bankAccountId = uuid(text(payload, "bankAccountId"), "bankAccountId");
        String chequeNumber = text(payload, "chequeNumber");
        if (!CHEQUE_NUMBER.matcher(chequeNumber).matches()) {
            throw new InvalidChequeEventException("Invalid chequeNumber");
        }
        String category = text(payload, "category");
        if (!CATEGORIES.contains(category)) {
            throw new InvalidChequeEventException("Invalid category");
        }
        BigDecimal amount = amount(text(payload, "amount"));
        LocalDate issueDate = date(text(payload, "issueDate"), "issueDate");
        LocalDate clearingDate = date(text(payload, "expectedClearingDate"), "expectedClearingDate");
        if (clearingDate.isBefore(issueDate)) {
            throw new InvalidChequeEventException("expectedClearingDate is before issueDate");
        }
        String status = text(payload, "status");
        if (!STATUSES.contains(status)) {
            throw new InvalidChequeEventException("Invalid status");
        }
        if (!(payload.get("version") instanceof Integer version) || version < 1) {
            throw new InvalidChequeEventException("Invalid version");
        }
        return new ChequeFields(chequeId, companyId, bankAccountId, chequeNumber, category, amount, issueDate,
                clearingDate, status, version);
    }

    static String text(Map<String, Object> payload, String field) {
        if (payload.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new InvalidChequeEventException("Missing or invalid " + field);
    }

    static UUID uuid(String value, String field) {
        try {
            if (value != null) {
                return UUID.fromString(value);
            }
        } catch (IllegalArgumentException e) {
            // reported below
        }
        throw new InvalidChequeEventException("Missing or invalid " + field);
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidChequeEventException("Invalid " + field);
        }
    }

    private static BigDecimal amount(String value) {
        if (AMOUNT.matcher(value).matches()) {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() > 0) {
                return amount;
            }
        }
        throw new InvalidChequeEventException("Invalid amount");
    }
}
