package com.flowledger.bankintegration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One entry in the bank-integration audit history. Written in the same transaction as the
 * change it describes and never updated; the database also rejects updates and deletes.
 */
@Entity
@Immutable
@Table(name = "bank_integration_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class BankIntegrationAuditEvent {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private String actorType;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 80, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 80, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

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

    // Changes in this service are always made by a signed-in user.
    public BankIntegrationAuditEvent(UUID companyId, UUID actorUserId, String action, String entityType,
                                     UUID entityId, String reason, Map<String, Object> beforeData,
                                     Map<String, Object> afterData) {
        this.companyId = companyId;
        this.actorType = "USER";
        this.actorUserId = actorUserId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.reason = reason;
        this.beforeData = beforeData;
        this.afterData = afterData;
    }
}
