package com.flowledger.bankintegration.entity;

import com.flowledger.bankintegration.statement.StatementError;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One uploaded statement file. The file itself is not stored; its SHA-256 hash identifies it.
 */
@Entity
@Table(name = "bank_statement_import_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class BankStatementImportBatch {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "filename", nullable = false, length = 255, updatable = false)
    private String filename;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(name = "file_format", nullable = false, length = 40, updatable = false)
    private String fileFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ImportBatchStatus status;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "imported_row_count", nullable = false)
    private int importedRowCount;

    @Column(name = "duplicate_row_count", nullable = false)
    private int duplicateRowCount;

    @Column(name = "skipped_row_count", nullable = false)
    private int skippedRowCount;

    @Column(name = "imported_by", nullable = false, updatable = false)
    private UUID importedBy;

    @Generated
    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "error_summary", columnDefinition = "text")
    private String errorSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors", nullable = false)
    private List<StatementError> validationErrors;

    private BankStatementImportBatch(UUID companyId, UUID bankAccountId, String filename, String fileSha256,
                                     String fileFormat, ImportBatchStatus status, UUID importedBy) {
        this.companyId = companyId;
        this.bankAccountId = bankAccountId;
        this.filename = filename;
        this.fileSha256 = fileSha256;
        this.fileFormat = fileFormat;
        this.status = status;
        this.importedBy = importedBy;
        this.validationErrors = List.of();
    }

    public static BankStatementImportBatch failed(UUID companyId, UUID bankAccountId, String filename, String fileSha256,
                                                  String fileFormat, UUID importedBy, List<StatementError> errors) {
        BankStatementImportBatch batch = new BankStatementImportBatch(
                companyId, bankAccountId, filename, fileSha256, fileFormat, ImportBatchStatus.FAILED, importedBy);
        batch.validationErrors = List.copyOf(errors);
        batch.errorSummary = errors.size() == 1
                ? errors.getFirst().message()
                : errors.size() + " problems were found in the statement.";
        return batch;
    }

    public static BankStatementImportBatch imported(UUID companyId, UUID bankAccountId, String filename,
                                                    String fileSha256, String fileFormat, UUID importedBy,
                                                    LocalDate periodStart, LocalDate periodEnd, int rowCount) {
        BankStatementImportBatch batch = new BankStatementImportBatch(
                companyId, bankAccountId, filename, fileSha256, fileFormat, ImportBatchStatus.IMPORTED, importedBy);
        batch.periodStart = periodStart;
        batch.periodEnd = periodEnd;
        batch.rowCount = rowCount;
        return batch;
    }

    public void recordOutcome(int importedRowCount, int duplicateRowCount, int skippedRowCount) {
        this.importedRowCount = importedRowCount;
        this.duplicateRowCount = duplicateRowCount;
        this.skippedRowCount = skippedRowCount;
    }
}
