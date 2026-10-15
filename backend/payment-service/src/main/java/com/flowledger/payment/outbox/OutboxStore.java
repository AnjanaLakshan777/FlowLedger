package com.flowledger.payment.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Database side of outbox publishing. Every method is one statement that commits on its own, so
 * no transaction is open while the publisher waits for the broker. Times come from the database
 * clock, never the application's.
 */
@Repository
@RequiredArgsConstructor
public class OutboxStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Reserves up to {@code limit} due events for one attempt each by moving next_attempt_at past
     * the lease and counting the attempt. Only publishable rows are eligible (rows from before the
     * activation boundary never are), and only the oldest pending event of each aggregate, so one
     * cheque's events are published in order. SKIP LOCKED lets several publishers run at once
     * without claiming the same row; an expired lease makes a row due again.
     *
     * @param companyId restricts claiming to one company's events when not null (tests use this so
     *                  they never touch other rows in a shared database)
     */
    public List<ClaimedEvent> claim(int limit, Duration lease, UUID companyId) {
        String company = companyId == null ? null : companyId.toString();
        List<ClaimedEvent> claimed = jdbcTemplate.query("""
                        WITH due AS (
                            SELECT o.id FROM outbox_events o
                            WHERE o.publishable AND o.published_at IS NULL
                              AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
                              AND (CAST(? AS text) IS NULL OR o.payload ->> 'companyId' = CAST(? AS text))
                              AND NOT EXISTS (
                                  SELECT 1 FROM outbox_events p
                                  WHERE p.aggregate_id = o.aggregate_id AND p.publishable AND p.published_at IS NULL
                                    AND (p.created_at, p.id) < (o.created_at, o.id))
                            ORDER BY o.created_at, o.id
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED)
                        UPDATE outbox_events e
                        SET next_attempt_at = now() + make_interval(secs => ?), attempt_count = e.attempt_count + 1
                        FROM due
                        WHERE e.id = due.id
                        RETURNING e.id, e.aggregate_type, e.aggregate_id, e.event_type, e.schema_version,
                                  e.payload::text AS payload, e.payload ->> 'companyId' AS company_id, e.attempt_count,
                                  e.created_at
                        """,
                (rs, row) -> new ClaimedEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("schema_version"),
                        rs.getString("payload"),
                        rs.getString("company_id"),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("created_at").toInstant()),
                company, company, limit, lease.toMillis() / 1000.0);
        return claimed.stream()
                .sorted(Comparator.comparing(ClaimedEvent::createdAt).thenComparing(ClaimedEvent::id))
                .toList();
    }

    // Only after a confirmed, routable publish.
    public void markPublished(UUID id) {
        jdbcTemplate.update("""
                UPDATE outbox_events SET published_at = now(), next_attempt_at = NULL, last_error = NULL
                WHERE id = ? AND published_at IS NULL
                """, id);
    }

    // The event stays pending; error is a short, sanitized description.
    public void scheduleRetry(UUID id, Duration delay, String error) {
        jdbcTemplate.update("""
                UPDATE outbox_events SET next_attempt_at = now() + make_interval(secs => ?), last_error = ?
                WHERE id = ? AND published_at IS NULL
                """, delay.toMillis() / 1000.0, error, id);
    }
}
