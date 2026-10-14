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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Bank account metadata only. It holds no balance, and the full account number is not stored
 * (account_number_encrypted stays unmapped until encryption is decided).
 */
@Entity
@Table(name = "bank_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class BankAccount {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "bank_name", nullable = false, length = 160)
    private String bankName;

    @Column(name = "account_name", nullable = false, length = 160)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 24)
    private BankAccountType accountType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_number_last4", length = 4)
    private String accountNumberLast4;

    @Generated
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_mode", nullable = false, length = 24)
    private IntegrationMode integrationMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BankAccountStatus status;

    @Generated
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public BankAccount(UUID companyId, String bankName, String accountName, BankAccountType accountType,
                       String accountNumberLast4, IntegrationMode integrationMode, BankAccountStatus status) {
        this.companyId = companyId;
        this.bankName = bankName;
        this.accountName = accountName;
        this.accountType = accountType;
        this.accountNumberLast4 = accountNumberLast4;
        this.integrationMode = integrationMode;
        this.status = status;
    }
}
