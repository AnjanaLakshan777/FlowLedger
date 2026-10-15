package com.flowledger.bankintegration.statement;

import com.flowledger.bankintegration.exception.InvalidStatementFileException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * A validated statement upload. The content is only held in memory for the import; the file
 * itself is not stored. The size limit matches spring.servlet.multipart.max-file-size.
 */
public record StatementUpload(String filename, byte[] content) {

    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_FILENAME_LENGTH = 255;
    // Browsers report CSV files under several types; the extension is checked as well.
    private static final Set<String> CONTENT_TYPES = Set.of(
            "text/csv", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream");

    public static StatementUpload from(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidStatementFileException("Choose a statement file to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new InvalidStatementFileException("The statement file must be at most 2 MB.");
        }
        String filename = safeFilename(file.getOriginalFilename());
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new InvalidStatementFileException("Only CSV statement files (.csv) are supported.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !CONTENT_TYPES.contains(baseType(contentType))) {
            throw new InvalidStatementFileException("Only CSV statement files (.csv) are supported.");
        }
        try {
            return new StatementUpload(filename, file.getBytes());
        } catch (IOException e) {
            throw new InvalidStatementFileException("The statement file could not be read.");
        }
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
}
