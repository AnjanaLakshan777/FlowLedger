package com.flowledger.forecast.backfill;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * cheque_backfill_runs and cheque_backfill_rejections. Statements outside a transaction commit on
 * their own; {@link #lock} and {@link #advance} belong in the page transaction.
 */
@Repository
@RequiredArgsConstructor
public class ChequeBackfillStore {

    private static final String COLUMNS = """
            id, status, cursor_after_id, pages_processed, cheques_seen, applied_count, stale_count,
            rejected_count, failure_count, last_error
            """;

    private static final RowMapper<RunState> RUN = (rs, rowNum) -> new RunState(
            rs.getObject("id", UUID.class),
            rs.getString("status"),
            rs.getObject("cursor_after_id", UUID.class),
            rs.getInt("pages_processed"),
            rs.getLong("cheques_seen"),
            rs.getLong("applied_count"),
            rs.getLong("stale_count"),
            rs.getLong("rejected_count"),
            rs.getInt("failure_count"),
            rs.getString("last_error"));

    private final JdbcTemplate jdbcTemplate;

    public record RunState(UUID id, String status, UUID cursorAfterId, int pagesProcessed, long chequesSeen,
                           long appliedCount, long staleCount, long rejectedCount, int failureCount,
                           String lastError) {

        public boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    /**
     * The run in progress, or a new one from the start of the scan. The unique index on runs in
     * progress makes concurrent callers end up with the same run.
     */
    public RunState startOrResume() {
        jdbcTemplate.update("""
                INSERT INTO cheque_backfill_runs (status) VALUES ('IN_PROGRESS')
                ON CONFLICT (status) WHERE status = 'IN_PROGRESS' DO NOTHING
                """);
        return findInProgress().orElseThrow(() -> new IllegalStateException("No backfill run in progress"));
    }

    public Optional<RunState> findInProgress() {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM cheque_backfill_runs WHERE status = 'IN_PROGRESS'", RUN));
    }

    public RunState load(UUID runId) {
        return jdbcTemplate.queryForObject("SELECT " + COLUMNS + " FROM cheque_backfill_runs WHERE id = ?",
                RUN, runId);
    }

    // Locks the run until the page transaction ends, so two workers never apply pages at once.
    public RunState lock(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + COLUMNS + " FROM cheque_backfill_runs WHERE id = ? FOR UPDATE", RUN, runId);
    }

    public void advance(UUID runId, UUID cursorAfterId, long applied, long stale, long rejected, boolean completed) {
        jdbcTemplate.update("""
                        UPDATE cheque_backfill_runs SET
                            cursor_after_id = ?,
                            pages_processed = pages_processed + 1,
                            cheques_seen = cheques_seen + ?,
                            applied_count = applied_count + ?,
                            stale_count = stale_count + ?,
                            rejected_count = rejected_count + ?,
                            status = CASE WHEN ? THEN 'COMPLETED' ELSE status END,
                            completed_at = CASE WHEN ? THEN now() ELSE completed_at END,
                            updated_at = now()
                        WHERE id = ?
                        """,
                cursorAfterId, applied + stale + rejected, applied, stale, rejected, completed, completed, runId);
    }

    public void recordRejection(UUID runId, UUID chequeId, UUID companyId, String reason) {
        jdbcTemplate.update("""
                        INSERT INTO cheque_backfill_rejections (backfill_run_id, cheque_id, company_id, reason)
                        VALUES (?, ?, ?, ?)
                        """,
                runId, chequeId, companyId, truncate(reason, 200));
    }

    public void recordFailure(UUID runId, String reason) {
        jdbcTemplate.update("""
                        UPDATE cheque_backfill_runs SET
                            failure_count = failure_count + 1,
                            last_error = ?,
                            last_failed_at = now(),
                            updated_at = now()
                        WHERE id = ?
                        """,
                truncate(reason, 300), runId);
    }

    private static String truncate(String text, int max) {
        String line = text == null ? "unknown" : text.replaceAll("\\s+", " ");
        return line.length() <= max ? line : line.substring(0, max);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }
}
