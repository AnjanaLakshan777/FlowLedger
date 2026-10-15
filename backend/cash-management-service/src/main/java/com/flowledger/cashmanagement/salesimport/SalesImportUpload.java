package com.flowledger.cashmanagement.salesimport;

import com.flowledger.cashmanagement.exception.InvalidImportRequestException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * A checked historical sales upload. The content is only held in memory while it is validated;
 * the file itself is not stored, only its name, size and SHA-256. The size limit matches
 * spring.servlet.multipart.max-file-size.
 */
public record SalesImportUpload(String filename, byte[] content, String sha256) {

    public static final long MAX_BYTES = 1024L * 1024;
    private static final int MAX_FILENAME_LENGTH = 255;
    // Browsers report CSV files under several types; the extension is checked as well.
    private static final Set<String> CONTENT_TYPES = Set.of(
            "text/csv", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream");

    public static SalesImportUpload from(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImportRequestException("file", "Choose a CSV file to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new InvalidImportRequestException("file", "The file must be at most 1 MB.");
        }
        String filename = safeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new InvalidImportRequestException("file",
                    "Only CSV files (.csv) are supported. In Excel, use Save As > CSV UTF-8.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !CONTENT_TYPES.contains(baseType(contentType))) {
            throw new InvalidImportRequestException("file",
                    "Only CSV files (.csv) are supported. In Excel, use Save As > CSV UTF-8.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidImportRequestException("file", "The file could not be read.");
        }
        return new SalesImportUpload(filename, content, sha256(content));
    }

    // Keeps only the name (no client path) and characters safe to store and show.
    static String safeFilename(String original) {
        if (original == null) {
            return "";
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[^A-Za-z0-9 ._()-]", "_").strip();
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(name.length() - MAX_FILENAME_LENGTH);
        }
        return name;
    }

    private static String baseType(String contentType) {
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
