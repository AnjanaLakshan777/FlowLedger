package com.flowledger.bankintegration.dto;

import com.flowledger.bankintegration.entity.BankStatementImportBatch;
import com.flowledger.bankintegration.entity.ImportBatchStatus;
import com.flowledger.bankintegration.statement.StatementError;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An import batch. {@code earlierImportsOfSameFile} lists earlier successful imports of a file
 * with the same SHA-256 hash into the same account; the file is still processed, and rows already
 * imported are counted as duplicates.
 */
public record StatementImportResponse(
        UUID id,
        UUID companyId,
        UUID bankAccountId,
        String filename,
        String fileSha256,
        String fileFormat,
        ImportBatchStatus status,
        LocalDate periodStart,
        LocalDate periodEnd,
        int rowCount,
        int importedRowCount,
        int duplicateRowCount,
        int skippedRowCount,
        UUID importedBy,
        Instant importedAt,
        String errorSummary,
        List<StatementError> validationErrors,
        List<UUID> earlierImportsOfSameFile,
        Instant removedAt,
        UUID removedBy
) {

    public static StatementImportResponse from(BankStatementImportBatch batch, List<UUID> earlierImportsOfSameFile) {
        return new StatementImportResponse(
                batch.getId(),
                batch.getCompanyId(),
                batch.getBankAccountId(),
                batch.getFilename(),
                batch.getFileSha256(),
                batch.getFileFormat(),
                batch.getStatus(),
                batch.getPeriodStart(),
                batch.getPeriodEnd(),
                batch.getRowCount(),
                batch.getImportedRowCount(),
                batch.getDuplicateRowCount(),
                batch.getSkippedRowCount(),
                batch.getImportedBy(),
                batch.getImportedAt(),
                batch.getErrorSummary(),
                batch.getValidationErrors(),
                earlierImportsOfSameFile,
                batch.getRemovedAt(),
                batch.getRemovedBy()
        );
    }
}
