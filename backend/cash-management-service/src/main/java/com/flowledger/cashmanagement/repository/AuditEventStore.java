package com.flowledger.cashmanagement.repository;

import com.flowledger.cashmanagement.dto.DailySaleCorrectionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
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

    // The latest correction reason of each corrected daily sale of the company, by sale ID.
    public Map<UUID, String> latestCorrectionReasons(UUID companyId) {
        Map<UUID, String> reasons = new HashMap<>();
        jdbcTemplate.query("""
                        SELECT DISTINCT ON (entity_id) entity_id, reason
                        FROM cash_management_audit_events
                        WHERE company_id = ? AND entity_type = 'DAILY_SALE' AND action = 'DAILY_SALE_CORRECTED'
                        ORDER BY entity_id, occurred_at DESC, (after_data ->> 'version')::int DESC
                        """,
                rs -> {
                    reasons.put(rs.getObject("entity_id", UUID.class), rs.getString("reason"));
                }, companyId);
        return reasons;
    }

    // The corrections of one daily sale, newest first.
    public List<DailySaleCorrectionResponse> dailySaleCorrections(UUID companyId, UUID saleId) {
        return jdbcTemplate.query("""
                        SELECT actor_user_id, occurred_at, reason,
                               (before_data ->> 'cashSalesAmount')::numeric AS cash_before,
                               (before_data ->> 'cardSalesAmount')::numeric AS card_before,
                               (after_data ->> 'cashSalesAmount')::numeric AS cash_after,
                               (after_data ->> 'cardSalesAmount')::numeric AS card_after
                        FROM cash_management_audit_events
                        WHERE company_id = ? AND entity_type = 'DAILY_SALE' AND entity_id = ?
                          AND action = 'DAILY_SALE_CORRECTED'
                        ORDER BY occurred_at DESC, (after_data ->> 'version')::int DESC
                        """,
                (rs, n) -> new DailySaleCorrectionResponse(rs.getObject("actor_user_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("reason"),
                        rs.getBigDecimal("cash_before"), rs.getBigDecimal("card_before"),
                        rs.getBigDecimal("cash_after"), rs.getBigDecimal("card_after")),
                companyId, saleId);
    }

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
