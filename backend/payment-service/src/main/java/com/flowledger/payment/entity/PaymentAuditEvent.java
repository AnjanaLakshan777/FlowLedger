package com.flowledger.payment.entity;

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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One entry in a cheque's audit history. Written in the same transaction as the change it
 * describes and never updated; the database also rejects updates and deletes.
 */
@Entity
@Immutable
@Table(name = "payment_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class PaymentAuditEvent {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 80, updatable = false)
    private PaymentAuditAction action;

    @Column(name = "cheque_id", nullable = false, updatable = false)
    private UUID chequeId;

    @Generated
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "reason", columnDefinition = "text", updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data", updatable = false)
    private Map<String, Object> beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data", updatable = false)
    private Map<String, Object> afterData;

    public PaymentAuditEvent(Cheque cheque, PaymentAuditAction action, UUID actorUserId, String reason,
                             Map<String, Object> beforeData) {
        this.companyId = cheque.getCompanyId();
        this.chequeId = cheque.getId();
        this.action = action;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.beforeData = beforeData;
        this.afterData = cheque.snapshot();
    }
}
