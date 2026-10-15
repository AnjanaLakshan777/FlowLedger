package com.flowledger.bankintegration.statement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deduplication fingerprints for statement rows that have no bank-provided transaction ID.
 * <p>
 * The running balance is part of the fingerprint, so legitimately repeated transactions (same
 * date, description and amount) stay distinct: each leaves a different balance. Rows that are
 * identical even in balance (possible when money goes out and back in) get an occurrence number
 * counted oldest first, so the same statement always produces the same fingerprints. The same
 * row in an overlapping later statement produces the same fingerprint and is not imported twice.
 */
public final class TransactionFingerprints {

    private TransactionFingerprints() {
    }

    public static List<String> of(List<StatementLine> transactionsOldestFirst) {
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> fingerprints = new ArrayList<>();
        for (StatementLine line : transactionsOldestFirst) {
            String key = String.join("|",
                    line.transactionDate().toString(),
                    line.direction().name(),
                    line.amount().toPlainString(),
                    line.runningBalance().toPlainString(),
                    line.description().strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT),
                    line.chequeNumber() == null ? "" : line.chequeNumber(),
                    line.branchCode() == null ? "" : line.branchCode());
            int occurrence = occurrences.merge(key, 1, Integer::sum);
            fingerprints.add(sha256(occurrence == 1 ? key : key + "|#" + occurrence));
        }
        return fingerprints;
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
