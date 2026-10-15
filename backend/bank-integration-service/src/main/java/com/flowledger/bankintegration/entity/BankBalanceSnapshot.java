package com.flowledger.bankintegration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A confirmed account balance at a point in time. Rows are inserted by StatementImportStore and
 * never change; through JPA they are only read.
 */
@Entity
@Immutable
@Table(name = "bank_balance_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class BankBalanceSnapshot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "balance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal balanceAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance_as_of", nullable = false, updatable = false)
    private Instant balanceAsOf;

    @Column(name = "source_type", nullable = false, length = 24, updatable = false)
    private String sourceType;

    @Column(name = "source_import_batch_id", updatable = false)
    private UUID sourceImportBatchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
