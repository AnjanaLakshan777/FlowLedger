package com.flowledger.bankintegration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A transaction as the bank recorded it. Imported rows are inserted by StatementImportStore so
 * duplicates can be skipped atomically; through JPA only the classification ever changes.
 */
@Entity
@Table(name = "bank_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class BankTransaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "transaction_date", nullable = false, updatable = false)
    private LocalDate transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8, updatable = false)
    private TransactionDirection direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "description", columnDefinition = "text", updatable = false)
    private String description;

    @Column(name = "running_balance", precision = 19, scale = 2, updatable = false)
    private BigDecimal runningBalance;

    @Column(name = "cheque_number", length = 40, updatable = false)
    private String chequeNumber;

    @Column(name = "branch_code", length = 20, updatable = false)
    private String branchCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private TransactionCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_method", nullable = false, length = 24)
    private ClassificationMethod classificationMethod;

    @Column(name = "classified_by")
    private UUID classifiedBy;

    @Column(name = "classified_at")
    private Instant classifiedAt;

    @Column(name = "source_import_batch_id", updatable = false)
    private UUID sourceImportBatchId;

    // Insertion order (oldest first per statement), used to keep the bank's order within a day.
    @Column(name = "entry_sequence", insertable = false, updatable = false)
    private Long entrySequence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Returns false when the category is unchanged, so nothing is updated or audited.
    public boolean classify(TransactionCategory newCategory, UUID actor, Instant now) {
        if (newCategory == category) {
            return false;
        }
        this.category = newCategory;
        this.classificationMethod = ClassificationMethod.MANUAL;
        this.classifiedBy = actor;
        this.classifiedAt = now;
        this.updatedAt = now;
        return true;
    }

    public Map<String, Object> classificationSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("category", category.name());
        snapshot.put("classificationMethod", classificationMethod.name());
        snapshot.put("classifiedBy", classifiedBy == null ? null : classifiedBy.toString());
        snapshot.put("classifiedAt", classifiedAt == null ? null : classifiedAt.toString());
        return snapshot;
    }
}
