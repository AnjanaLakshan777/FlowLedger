package com.flowledger.identity.entity;

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
 * One access change: a user added to a company, a role or membership status changed, or company
 * details edited. Written in the same transaction as the change and never updated. Details hold
 * IDs and the changed values only, never passwords.
 */
@Entity
@Immutable
@Table(name = "identity_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class IdentityAuditEvent {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false)
    private UUID companyId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 80, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 80, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Generated
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, updatable = false)
    private Map<String, Object> details;

    public IdentityAuditEvent(UUID companyId, UUID actorUserId, String action, String targetType, UUID targetId,
                              Map<String, Object> details) {
        this.companyId = companyId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
    }
}
