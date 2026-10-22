package com.flowledger.cashmanagement.entity;

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
import java.util.UUID;

/**
 * A company's recorded sales for one sales date. It is a record of sales only: the money's
 * arrival in the bank, and its date, come from bank transactions in bank-integration-service.
 * <p>
 * A day entered by hand can be corrected (amounts only); the version guards against two
 * corrections overwriting each other. Days from an approved import are corrected by reversing
 * the import.
 */
@Entity
@Table(name = "daily_sales")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class DailySale {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "cash_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cashSalesAmount;

    @Column(name = "card_sales_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal cardSalesAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_source", nullable = false, length = 20, updatable = false)
    private EntrySource entrySource;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Generated
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // Starts at 1, as the schema requires.
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    // Amounts are validated to at most two decimal places, so setScale never has to round.
    public DailySale(UUID companyId, LocalDate saleDate, BigDecimal cashSalesAmount, BigDecimal cardSalesAmount,
                     EntrySource entrySource, UUID recordedBy) {
        this.companyId = companyId;
        this.saleDate = saleDate;
        this.cashSalesAmount = cashSalesAmount.setScale(2);
        this.cardSalesAmount = cardSalesAmount.setScale(2);
        this.entrySource = entrySource;
        this.recordedBy = recordedBy;
    }

    // Returns false when the amounts are unchanged, so nothing is saved or audited.
    public boolean correct(BigDecimal cash, BigDecimal card, UUID actor, Instant now) {
        if (cashSalesAmount.compareTo(cash) == 0 && cardSalesAmount.compareTo(card) == 0) {
            return false;
        }
        this.cashSalesAmount = cash.setScale(2);
        this.cardSalesAmount = card.setScale(2);
        this.updatedBy = actor;
        this.updatedAt = now;
        return true;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("saleDate", saleDate.toString());
        snapshot.put("cashSalesAmount", cashSalesAmount.toPlainString());
        snapshot.put("cardSalesAmount", cardSalesAmount.toPlainString());
        snapshot.put("version", version);
        return snapshot;
    }

    public BigDecimal getTotalSalesAmount() {
        return cashSalesAmount.add(cardSalesAmount);
    }
}
