package com.flowledger.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A cheque payment the company has issued. The forecast deducts it on its expected clearing date;
 * nothing here records whether it has actually cleared.
 */
@Entity
@Table(name = "cheques")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class Cheque {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "cheque_number", nullable = false, length = 40)
    private String chequeNumber;

    @Column(name = "payee_name", nullable = false, length = 200)
    private String payeeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private ChequeCategory category;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "expected_clearing_date", nullable = false)
    private LocalDate expectedClearingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChequeStatus status;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Generated
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Optimistic locking: concurrent changes to the same cheque cannot overwrite each other.
    // Starts at 1, as the schema requires.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    public Cheque(UUID companyId, UUID bankAccountId, ChequeDetails details, UUID createdBy) {
        this.companyId = companyId;
        this.bankAccountId = bankAccountId;
        this.createdBy = createdBy;
        this.status = ChequeStatus.ISSUED;
        apply(details);
    }

    // Matches the UI: only a cheque still waiting to clear can be edited or cancelled. Once its
    // expected clearing date has passed it is treated as part of the actual bank balance.
    public boolean isChangeable(LocalDate today) {
        return status == ChequeStatus.ISSUED && expectedClearingDate.isAfter(today);
    }

    // Returns false when nothing changed, so no update, audit event or outbox event is needed.
    public boolean update(ChequeDetails details, UUID actor, Instant now) {
        if (details.equals(currentDetails())) {
            return false;
        }
        apply(details);
        markUpdated(actor, now);
        return true;
    }

    public void cancel(UUID actor, Instant now) {
        this.status = ChequeStatus.CANCELLED;
        markUpdated(actor, now);
    }

    // The business state recorded in audit history and outbox events. No personal or secret data.
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("chequeNumber", chequeNumber);
        snapshot.put("payeeName", payeeName);
        snapshot.put("category", category.name());
        snapshot.put("amount", amount.toPlainString());
        snapshot.put("issueDate", issueDate.toString());
        snapshot.put("expectedClearingDate", expectedClearingDate.toString());
        snapshot.put("status", status.name());
        snapshot.put("notes", notes);
        snapshot.put("bankAccountId", bankAccountId.toString());
        snapshot.put("version", version);
        return snapshot;
    }

    private ChequeDetails currentDetails() {
        return new ChequeDetails(chequeNumber, payeeName, category, amount, issueDate, expectedClearingDate, notes);
    }

    private void apply(ChequeDetails details) {
        this.chequeNumber = details.chequeNumber();
        this.payeeName = details.payeeName();
        this.category = details.category();
        this.amount = details.amount();
        this.issueDate = details.issueDate();
        this.expectedClearingDate = details.expectedClearingDate();
        this.notes = details.notes();
    }

    private void markUpdated(UUID actor, Instant now) {
        this.updatedBy = Objects.requireNonNull(actor);
        this.updatedAt = now;
    }
}
