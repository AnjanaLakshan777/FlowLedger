package com.flowledger.cashmanagement.entity;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A company's recorded sales for one sales date. It is a record of sales only: the money's
 * arrival in the bank, and its date, come from bank transactions in bank-integration-service.
 * <p>
 * updated_by, updated_at and version stay unmapped until corrections are designed; their
 * database defaults apply.
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

    public BigDecimal getTotalSalesAmount() {
        return cashSalesAmount.add(cardSalesAmount);
    }
}
