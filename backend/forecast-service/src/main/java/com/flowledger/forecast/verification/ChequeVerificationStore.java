package com.flowledger.forecast.verification;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the evidence for a projection check from forecast's own tables and stores the results in
 * cheque_projection_verifications.
 */
@Repository
@RequiredArgsConstructor
public class ChequeVerificationStore {

    private final JdbcTemplate jdbcTemplate;

    public record Figures(long chequeCount, long issuedCount, long cancelledCount, long versionSum) {

        static final Figures NONE = new Figures(0, 0, 0, 0);
    }

    /** Projected figures per company, all from one statement, and the database time just before it. */
    public record Projection(OffsetDateTime readAt, Map<UUID, Figures> companies) {
    }

    /**
     * @param completedRunId     the latest completed backfill run, if any
     * @param rejectedCompanies  companies with rejected items in that run or in a run in progress
     * @param rejectedUnknown    whether such a rejection has no company (its company is unknown)
     */
    public record BackfillEvidence(UUID completedRunId, Set<UUID> rejectedCompanies, boolean rejectedUnknown) {
    }

    public record Result(UUID companyId, String status, String reason, UUID backfillRunId, Figures projected,
                         Figures payment, Long deadLetterCount, OffsetDateTime projectionReadAt) {
    }

    public Projection projection(UUID companyId) {
        OffsetDateTime readAt = jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        String sql = """
                SELECT company_id,
                       count(*) AS cheque_count,
                       count(*) FILTER (WHERE status = 'ISSUED') AS issued_count,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_count,
                       coalesce(sum(source_version), 0) AS version_sum
                FROM forecast_cheques
                %s
                GROUP BY company_id
                """.formatted(companyId == null ? "" : "WHERE company_id = ?");
        Map<UUID, Figures> companies = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            companies.put(rs.getObject("company_id", UUID.class), new Figures(rs.getLong("cheque_count"),
                    rs.getLong("issued_count"), rs.getLong("cancelled_count"), rs.getLong("version_sum")));
        }, companyId == null ? new Object[0] : new Object[]{companyId});
        return new Projection(readAt, companies);
    }

    public BackfillEvidence backfillEvidence() {
        List<UUID> completed = jdbcTemplate.queryForList("""
                SELECT id FROM cheque_backfill_runs
                WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC
                LIMIT 1
                """, UUID.class);
        UUID completedRunId = completed.isEmpty() ? null : completed.getFirst();

        Set<UUID> rejected = new HashSet<>();
        boolean[] unknown = {false};
        jdbcTemplate.query("""
                        SELECT DISTINCT r.company_id
                        FROM cheque_backfill_rejections r
                        JOIN cheque_backfill_runs run ON run.id = r.backfill_run_id
                        WHERE run.id = ? OR run.status = 'IN_PROGRESS'
                        """,
                rs -> {
                    UUID companyId = rs.getObject("company_id", UUID.class);
                    if (companyId == null) {
                        unknown[0] = true;
                    } else {
                        rejected.add(companyId);
                    }
                }, completedRunId);
        return new BackfillEvidence(completedRunId, rejected, unknown[0]);
    }

    /**
     * Stores the result unless a result based on a later projection read is already stored, so
     * concurrent checks cannot replace newer evidence with older.
     */
    public void save(Result result) {
        Figures payment = result.payment();
        jdbcTemplate.update("""
                        INSERT INTO cheque_projection_verifications
                            (company_id, status, reason, backfill_run_id, projected_cheque_count,
                             projected_issued_count, projected_cancelled_count, projected_version_sum,
                             payment_cheque_count, payment_issued_count, payment_cancelled_count, payment_version_sum,
                             dead_letter_count, projection_read_at, checked_at, verified_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(),
                                CASE WHEN ? = 'VERIFIED' THEN now() END)
                        ON CONFLICT (company_id) DO UPDATE SET
                            status = EXCLUDED.status,
                            reason = EXCLUDED.reason,
                            backfill_run_id = EXCLUDED.backfill_run_id,
                            projected_cheque_count = EXCLUDED.projected_cheque_count,
                            projected_issued_count = EXCLUDED.projected_issued_count,
                            projected_cancelled_count = EXCLUDED.projected_cancelled_count,
                            projected_version_sum = EXCLUDED.projected_version_sum,
                            payment_cheque_count = EXCLUDED.payment_cheque_count,
                            payment_issued_count = EXCLUDED.payment_issued_count,
                            payment_cancelled_count = EXCLUDED.payment_cancelled_count,
                            payment_version_sum = EXCLUDED.payment_version_sum,
                            dead_letter_count = EXCLUDED.dead_letter_count,
                            projection_read_at = EXCLUDED.projection_read_at,
                            checked_at = EXCLUDED.checked_at,
                            verified_at = EXCLUDED.verified_at,
                            check_count = cheque_projection_verifications.check_count + 1
                        WHERE cheque_projection_verifications.projection_read_at <= EXCLUDED.projection_read_at
                        """,
                result.companyId(), result.status(), result.reason(), result.backfillRunId(),
                result.projected().chequeCount(), result.projected().issuedCount(),
                result.projected().cancelledCount(), result.projected().versionSum(),
                payment == null ? null : payment.chequeCount(), payment == null ? null : payment.issuedCount(),
                payment == null ? null : payment.cancelledCount(), payment == null ? null : payment.versionSum(),
                result.deadLetterCount(), result.projectionReadAt(), result.status());
    }

    /**
     * @param verifiedWithinMaxAge whether the stored status is VERIFIED and was verified at most
     *                             maxAge ago, both by the database clock
     */
    public record Latest(String status, String reason, boolean verifiedWithinMaxAge) {
    }

    public Optional<Latest> latest(UUID companyId, Duration maxAge) {
        return jdbcTemplate.query("""
                        SELECT status, reason,
                               status = 'VERIFIED' AND verified_at >= now() - make_interval(secs => ?)
                                   AS verified_within_max_age
                        FROM cheque_projection_verifications
                        WHERE company_id = ?
                        """,
                (rs, rowNum) -> new Latest(rs.getString("status"), rs.getString("reason"),
                        rs.getBoolean("verified_within_max_age")),
                maxAge.toMillis() / 1000.0, companyId).stream().findFirst();
    }

    public Optional<Map<String, Object>> find(UUID companyId) {
        return jdbcTemplate.queryForList("SELECT * FROM cheque_projection_verifications WHERE company_id = ?",
                companyId).stream().findFirst();
    }
}
