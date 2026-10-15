package com.flowledger.cashmanagement.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Appends to cash_management_audit_events. Call it inside the transaction of the change it
 * records, so both commit or neither does. The table rejects updates and deletes.
 */
@Repository
@RequiredArgsConstructor
public class AuditEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public void record(UUID companyId, UUID actorUserId, String action, String entityType, UUID entityId,
                       String reason, Map<String, ?> before, Map<String, ?> after) {
        jdbcTemplate.update("""
                        INSERT INTO cash_management_audit_events
                            (company_id, actor_type, actor_user_id, action, entity_type, entity_id, reason,
                             before_data, after_data)
                        VALUES (?, 'USER', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        """,
                companyId, actorUserId, action, entityType, entityId, reason,
                before == null ? null : jsonMapper.writeValueAsString(before),
                after == null ? null : jsonMapper.writeValueAsString(after));
    }
}
