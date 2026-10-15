package com.flowledger.forecast.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Puts a company's cheques into forecast_cheques, as the event consumer or the backfill would, and
 * stores a fresh VERIFIED result for the company backed by a completed backfill run, so a forecast
 * can use them. For tests of the forecast itself; verification has its own tests. In a
 * {@code @Transactional} test everything is rolled back; otherwise call {@link #deleteAll()}.
 */
public final class ProjectedCheques {

    public record Cheque(UUID chequeId, UUID accountId, String number, String amount, LocalDate clearing,
                         String status, int version) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Set<UUID> companies = new HashSet<>();
    private UUID backfillRunId;

    public ProjectedCheques(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static Cheque cheque(UUID chequeId, UUID accountId, String number, String amount, LocalDate clearing,
                                String status) {
        return new Cheque(chequeId, accountId, number, amount, clearing, status, 1);
    }

    /** Replaces the company's projected cheques and marks the projection verified now. */
    public void cheques(UUID companyId, List<Cheque> cheques) {
        companies.add(companyId);
        jdbcTemplate.update("DELETE FROM forecast_cheques WHERE company_id = ?", companyId);
        for (Cheque cheque : cheques) {
            jdbcTemplate.update("""
                            INSERT INTO forecast_cheques (cheque_id, company_id, bank_account_id, cheque_number,
                                category, amount, issue_date, expected_clearing_date, status, source_version,
                                projection_source)
                            VALUES (?, ?, ?, ?, 'SUPPLIER_PAYMENT', ?, ?, ?, ?, ?, 'BACKFILL')
                            """,
                    cheque.chequeId(), companyId, cheque.accountId(), cheque.number(), new BigDecimal(cheque.amount()),
                    cheque.clearing(), cheque.clearing(), cheque.status(), cheque.version());
        }
        long count = cheques.size();
        long issued = cheques.stream().filter(cheque -> "ISSUED".equals(cheque.status())).count();
        long versions = cheques.stream().mapToLong(Cheque::version).sum();
        jdbcTemplate.update("""
                        INSERT INTO cheque_projection_verifications (company_id, status, reason, backfill_run_id,
                            projected_cheque_count, projected_issued_count, projected_cancelled_count,
                            projected_version_sum, payment_cheque_count, payment_issued_count,
                            payment_cancelled_count, payment_version_sum, dead_letter_count, projection_read_at,
                            verified_at)
                        VALUES (?, 'VERIFIED', 'MATCHED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now(), now())
                        ON CONFLICT (company_id) DO UPDATE SET
                            status = 'VERIFIED', reason = 'MATCHED', backfill_run_id = EXCLUDED.backfill_run_id,
                            projected_cheque_count = EXCLUDED.projected_cheque_count,
                            projected_issued_count = EXCLUDED.projected_issued_count,
                            projected_cancelled_count = EXCLUDED.projected_cancelled_count,
                            projected_version_sum = EXCLUDED.projected_version_sum,
                            payment_cheque_count = EXCLUDED.payment_cheque_count,
                            payment_issued_count = EXCLUDED.payment_issued_count,
                            payment_cancelled_count = EXCLUDED.payment_cancelled_count,
                            payment_version_sum = EXCLUDED.payment_version_sum,
                            dead_letter_count = 0, projection_read_at = now(), verified_at = now()
                        """,
                companyId, backfillRun(), count, issued, count - issued, versions, count, issued, count - issued,
                versions);
    }

    public void deleteAll() {
        for (UUID companyId : companies) {
            jdbcTemplate.update("DELETE FROM cheque_projection_verifications WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM forecast_cheques WHERE company_id = ?", companyId);
        }
        if (backfillRunId != null) {
            jdbcTemplate.update("DELETE FROM cheque_backfill_runs WHERE id = ?", backfillRunId);
        }
        companies.clear();
        backfillRunId = null;
    }

    private UUID backfillRun() {
        if (backfillRunId == null) {
            backfillRunId = jdbcTemplate.queryForObject(
                    "INSERT INTO cheque_backfill_runs (status, completed_at) VALUES ('COMPLETED', now()) RETURNING id",
                    UUID.class);
        }
        return backfillRunId;
    }
}
