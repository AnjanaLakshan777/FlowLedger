package com.flowledger.forecast.verification;

import com.flowledger.forecast.backfill.ChequeBackfill;
import com.flowledger.forecast.client.PaymentInternalClient;
import com.flowledger.forecast.events.ChequeEvent;
import com.flowledger.forecast.events.ChequeEventProcessor;
import com.flowledger.forecast.support.FakePaymentInternalApi;
import com.flowledger.forecast.verification.ChequeProjectionVerifier.CompanyResult;
import com.flowledger.forecast.verification.ChequeProjectionVerifier.Reason;
import com.flowledger.forecast.verification.ChequeProjectionVerifier.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/**
 * Cheque projection verification against the local forecast PostgreSQL database, a fake
 * payment-service internal API, and a stubbed dead-letter queue count. The projection is filled by
 * the real backfill and event processor. Test rows are deleted afterwards; the tests skip
 * themselves if the database holds projection rows or completed backfill runs from elsewhere,
 * because a check over all companies would then include or rely on them.
 */
@SpringBootTest
class ChequeProjectionVerifierIntegrationTest {

    private static final FakePaymentInternalApi payment = FakePaymentInternalApi.instance();
    private static final int MAX_ATTEMPTS = 3;

    @DynamicPropertySource
    static void paymentService(DynamicPropertyRegistry registry) {
        registry.add("flowledger.payment.base-url", payment::baseUrl);
        registry.add("flowledger.cheque-verification.max-attempts", () -> MAX_ATTEMPTS);
    }

    @Value("${flowledger.payment.internal-api-secret}")
    private String internalKey;

    @Autowired
    private ChequeProjectionVerifier verifier;

    @Autowired
    private ChequeBackfill backfill;

    @Autowired
    private ChequeEventProcessor eventProcessor;

    @Autowired
    private PaymentInternalClient paymentClient;

    @Autowired
    private ChequeVerificationStore store;

    @Autowired
    private ChequeVerificationProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private DeadLetterEvidence deadLetterEvidence;

    private final Set<UUID> companies = new HashSet<>();
    private final Set<UUID> events = new HashSet<>();
    private Set<UUID> existingRuns;

    @BeforeEach
    void setUp() {
        assumeTrue(count("SELECT count(*) FROM forecast_cheques") == 0, "projection rows from elsewhere");
        assumeTrue(count("SELECT count(*) FROM cheque_backfill_runs") == 0, "backfill runs from elsewhere");
        assumeTrue(count("SELECT count(*) FROM cheque_projection_verifications") == 0, "verifications from elsewhere");
        payment.reset(internalKey);
        when(deadLetterEvidence.deadLetterCount()).thenReturn(OptionalLong.of(0));
        existingRuns = new HashSet<>(jdbcTemplate.queryForList("SELECT id FROM cheque_backfill_runs", UUID.class));
    }

    @AfterEach
    void deleteTestData() {
        for (UUID companyId : companies) {
            jdbcTemplate.update("DELETE FROM cheque_projection_verifications WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM forecast_cheques WHERE company_id = ?", companyId);
        }
        for (UUID eventId : events) {
            jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
        for (UUID runId : jdbcTemplate.queryForList("SELECT id FROM cheque_backfill_runs", UUID.class)) {
            if (existingRuns != null && !existingRuns.contains(runId)) {
                jdbcTemplate.update("DELETE FROM cheque_backfill_rejections WHERE backfill_run_id = ?", runId);
                jdbcTemplate.update("DELETE FROM cheque_backfill_runs WHERE id = ?", runId);
            }
        }
    }

    // Verified

    @Test
    void shouldVerifyEveryCompanyWhoseProjectionMatches() {
        UUID first = company();
        UUID second = company();
        payment.cheque(UUID.randomUUID(), first, "ISSUED", 1);
        payment.cheque(UUID.randomUUID(), first, "CANCELLED", 3);
        payment.cheque(UUID.randomUUID(), second, "ISSUED", 2);
        UUID runId = backfill.run().runId();

        List<CompanyResult> results = verifier.verifyAll();

        assertThat(results).extracting(CompanyResult::companyId).containsExactlyInAnyOrder(first, second);
        assertThat(results).extracting(CompanyResult::status).containsOnly(Status.VERIFIED);
        Map<String, Object> row = verification(first);
        assertThat(row).containsEntry("status", "VERIFIED").containsEntry("reason", "MATCHED")
                .containsEntry("backfill_run_id", runId).containsEntry("projected_cheque_count", 2L)
                .containsEntry("projected_issued_count", 1L).containsEntry("projected_cancelled_count", 1L)
                .containsEntry("projected_version_sum", 4L).containsEntry("payment_cheque_count", 2L)
                .containsEntry("payment_version_sum", 4L).containsEntry("dead_letter_count", 0L)
                .containsEntry("check_count", 1);
        assertThat(row.get("verified_at")).isNotNull();
        // The backfill run stays COMPLETED; verification is recorded separately.
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM cheque_backfill_runs WHERE id = ?",
                String.class, runId)).isEqualTo("COMPLETED");
    }

    @Test
    void shouldVerifyOneCompanyWithItsOwnSummary() {
        UUID companyId = company();
        UUID other = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        payment.cheque(UUID.randomUUID(), other, "ISSUED", 1);
        backfill.run();
        payment.cheque(UUID.randomUUID(), other, "ISSUED", 1);

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.status()).isEqualTo(Status.VERIFIED);
        assertThat(payment.requestedSummaries()).containsExactly(companyId.toString());
        assertThat(store.find(other)).isEmpty();
    }

    @Test
    void shouldVerifyACompanyWithoutChequesWithZeroFigures() {
        payment.cheque(UUID.randomUUID(), company(), "ISSUED", 1);
        backfill.run();
        UUID empty = company();

        CompanyResult result = verifier.verifyCompany(empty);

        assertThat(result.status()).isEqualTo(Status.VERIFIED);
        assertThat(verification(empty)).containsEntry("projected_cheque_count", 0L)
                .containsEntry("payment_cheque_count", 0L);
    }

    @Test
    void shouldGiveTheSameResultWhenCheckedAgain() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        verifier.verifyCompany(companyId);
        OffsetDateTime firstRead = projectionReadAt(companyId);

        CompanyResult again = verifier.verifyCompany(companyId);

        assertThat(again.status()).isEqualTo(Status.VERIFIED);
        assertThat(verification(companyId)).containsEntry("check_count", 2).containsEntry("status", "VERIFIED");
        assertThat(projectionReadAt(companyId)).isAfter(firstRead);
        assertThat(count("SELECT count(*) FROM cheque_projection_verifications WHERE company_id = '"
                + companyId + "'")).isEqualTo(1);
    }

    // Not verified: missing evidence or known gaps

    @Test
    void shouldNotVerifyWithoutACompletedBackfill() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        payment.cheque(chequeId, companyId, "ISSUED", 1);
        eventProcessor.process(event(companyId, chequeId, 1, "ISSUED"));

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.reason()).isEqualTo(Reason.NO_COMPLETED_BACKFILL);
        assertThat(result.status()).isEqualTo(Status.NOT_VERIFIED);
        assertThat(verification(companyId)).containsEntry("status", "NOT_VERIFIED");
        assertThat(verification(companyId).get("verified_at")).isNull();
    }

    @Test
    void shouldNotVerifyACompanyWithRejectedBackfillItems() {
        UUID rejectedCompany = company();
        UUID cleanCompany = company();
        payment.cheque(UUID.randomUUID(), rejectedCompany, "ISSUED", 1);
        payment.cheque(UUID.randomUUID(), rejectedCompany, "ISSUED", 1).put("amount", "-1.00");
        payment.cheque(UUID.randomUUID(), cleanCompany, "ISSUED", 1);
        backfill.run();

        assertThat(verifier.verifyCompany(rejectedCompany).reason()).isEqualTo(Reason.BACKFILL_REJECTIONS);
        assertThat(verifier.verifyCompany(cleanCompany).status()).isEqualTo(Status.VERIFIED);
    }

    @Test
    void shouldNotVerifyAnyCompanyWhileARejectionHasNoKnownCompany() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        payment.cheque(UUID.randomUUID(), company(), "ISSUED", 1).put("companyId", "not-a-uuid");
        backfill.run();

        assertThat(verifier.verifyCompany(companyId).reason()).isEqualTo(Reason.BACKFILL_REJECTIONS);
    }

    @Test
    void shouldNotVerifyWhileEventsAreDeadLettered() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        when(deadLetterEvidence.deadLetterCount()).thenReturn(OptionalLong.of(2));

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.reason()).isEqualTo(Reason.DEAD_LETTERS_PRESENT);
        assertThat(result.status()).isEqualTo(Status.NOT_VERIFIED);
        assertThat(verification(companyId)).containsEntry("dead_letter_count", 2L);
    }

    @Test
    void shouldRetryWhenTheDeadLetterQueueCannotBeRead() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        when(deadLetterEvidence.deadLetterCount()).thenReturn(OptionalLong.empty());

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.reason()).isEqualTo(Reason.DEAD_LETTER_EVIDENCE_UNAVAILABLE);
        assertThat(result.status()).isEqualTo(Status.RETRY_REQUIRED);
        assertThat(verification(companyId).get("dead_letter_count")).isNull();
        assertThat(payment.requestedSummaries()).hasSize(MAX_ATTEMPTS);
    }

    @Test
    void shouldRetryWhenTheSummaryIsUnavailableOrInvalid() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        payment.failSummary(100);

        CompanyResult unavailable = verifier.verifyCompany(companyId);

        assertThat(unavailable.reason()).isEqualTo(Reason.SUMMARY_UNAVAILABLE);
        assertThat(unavailable.status()).isEqualTo(Status.RETRY_REQUIRED);
        assertThat(verification(companyId).get("payment_cheque_count")).isNull();
        assertThat(verification(companyId)).containsEntry("check_count", MAX_ATTEMPTS);

        payment.failSummary(0);
        payment.rewriteSummary(body -> {
            ((List<Map<String, Object>>) body.get("companies")).getFirst().put("issuedCount", 7);
            return body;
        });
        assertThat(verifier.verifyCompany(companyId).reason()).isEqualTo(Reason.SUMMARY_UNAVAILABLE);
    }

    @Test
    void shouldVerifyOnceTheSummaryAnswersAgain() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        payment.failSummary(1);

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.status()).isEqualTo(Status.VERIFIED);
        assertThat(verification(companyId)).containsEntry("check_count", 2);
    }

    // Mismatches

    @Test
    void shouldRetryWhenPaymentHasMoreCheques() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.reason()).isEqualTo(Reason.PROJECTION_BEHIND);
        assertThat(result.status()).isEqualTo(Status.RETRY_REQUIRED);
        assertThat(verification(companyId)).containsEntry("projected_cheque_count", 1L)
                .containsEntry("payment_cheque_count", 2L).containsEntry("check_count", MAX_ATTEMPTS);
        assertThat(payment.requestedSummaries()).hasSize(MAX_ATTEMPTS);
    }

    @Test
    void shouldRetryWhenPaymentHasANewerVersion() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        payment.cheque(chequeId, companyId, "ISSUED", 1);
        backfill.run();
        payment.cheque(chequeId, companyId, "CANCELLED", 2);

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.reason()).isEqualTo(Reason.PROJECTION_BEHIND);
        assertThat(verification(companyId)).containsEntry("projected_version_sum", 1L)
                .containsEntry("payment_version_sum", 2L);
    }

    @Test
    void shouldVerifyOnceAPendingEventArrives() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        payment.cheque(chequeId, companyId, "ISSUED", 1);
        backfill.run();
        // payment-service has version 2; its event is still on the way (outbox or queue).
        payment.cheque(chequeId, companyId, "CANCELLED", 2);
        AtomicInteger waits = new AtomicInteger();
        ChequeProjectionVerifier deliveringVerifier = new ChequeProjectionVerifier(paymentClient, store,
                deadLetterEvidence, properties, duration -> {
                    if (waits.getAndIncrement() == 0) {
                        eventProcessor.process(event(companyId, chequeId, 2, "CANCELLED"));
                    }
                });

        CompanyResult result = deliveringVerifier.verifyCompany(companyId);

        assertThat(result.status()).isEqualTo(Status.VERIFIED);
        assertThat(verification(companyId)).containsEntry("check_count", 2)
                .containsEntry("projected_cancelled_count", 1L);
    }

    @Test
    void shouldNotVerifyAChangeMadeBetweenReadingTheProjectionAndTheSummary() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        payment.cheque(chequeId, companyId, "ISSUED", 1);
        backfill.run();
        AtomicInteger summaries = new AtomicInteger();
        // The change commits in payment-service after the projection was read; its event reaches
        // forecast before the next check.
        payment.beforeSummary(() -> {
            if (summaries.getAndIncrement() == 0) {
                payment.cheque(chequeId, companyId, "ISSUED", 2).put("amount", "2000.00");
                payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
            }
        });
        AtomicInteger waits = new AtomicInteger();
        ChequeProjectionVerifier verifierWithEvents = new ChequeProjectionVerifier(paymentClient, store,
                deadLetterEvidence, properties, duration -> {
                    if (waits.getAndIncrement() == 0) {
                        // Only the first change arrives; the new cheque's event is still pending.
                        eventProcessor.process(event(companyId, chequeId, 2, "ISSUED"));
                    }
                });

        CompanyResult result = verifierWithEvents.verifyCompany(companyId);

        assertThat(result.status()).isEqualTo(Status.RETRY_REQUIRED);
        assertThat(result.reason()).isEqualTo(Reason.PROJECTION_BEHIND);
        assertThat(verification(companyId).get("verified_at")).isNull();
    }

    @Test
    void shouldNotVerifyAProjectionAheadOfPaymentService() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        UUID unknownCheque = UUID.randomUUID();
        eventProcessor.process(event(companyId, unknownCheque, 1, "ISSUED"));
        UUID projectionOnly = company();
        eventProcessor.process(event(projectionOnly, UUID.randomUUID(), 1, "ISSUED"));

        List<CompanyResult> results = verifier.verifyAll();

        assertThat(results).filteredOn(r -> r.companyId().equals(companyId)).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(Reason.PROJECTION_AHEAD));
        assertThat(results).filteredOn(r -> r.companyId().equals(projectionOnly)).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(Reason.PROJECTION_AHEAD));
        // Not a condition that time fixes: checked once.
        assertThat(payment.requestedSummaries()).hasSize(1);
    }

    @Test
    void shouldNotVerifyDifferentStatusesBehindEqualCountsAndVersions() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        payment.cheque(chequeId, companyId, "ISSUED", 1);
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        // Same version, different status: only possible if an assumption broke.
        jdbcTemplate.update("UPDATE forecast_cheques SET status = 'CANCELLED' WHERE cheque_id = ?", chequeId);

        CompanyResult result = verifier.verifyCompany(companyId);

        assertThat(result.reason()).isEqualTo(Reason.STATUS_COUNTS_DIFFER);
        assertThat(result.status()).isEqualTo(Status.NOT_VERIFIED);
    }

    @Test
    void shouldKeepTheResultOfTheLaterProjectionRead() {
        UUID companyId = company();
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);
        backfill.run();
        verifier.verifyCompany(companyId);
        Map<String, Object> newer = verification(companyId);

        // A slower, concurrent check that read the projection earlier finishes last.
        store.save(new ChequeVerificationStore.Result(companyId, "RETRY_REQUIRED", "PROJECTION_BEHIND",
                (UUID) newer.get("backfill_run_id"), new ChequeVerificationStore.Figures(0, 0, 0, 0), null, 0L,
                projectionReadAt(companyId).minusSeconds(1)));

        assertThat(verification(companyId)).containsEntry("status", "VERIFIED").containsEntry("check_count", 1);
    }

    // Helpers

    private UUID company() {
        UUID companyId = UUID.randomUUID();
        companies.add(companyId);
        return companyId;
    }

    private ChequeEvent event(UUID companyId, UUID chequeId, int version, String status) {
        UUID eventId = UUID.randomUUID();
        events.add(eventId);
        return new ChequeEvent(eventId, "CANCELLED".equals(status) ? "ChequeCancelled" : "ChequeUpdated", companyId,
                chequeId, UUID.fromString("5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f"), "000123", "SUPPLIER_PAYMENT",
                new BigDecimal("1500.00"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 22), status, version,
                Instant.parse("2026-10-15T08:30:00Z"));
    }

    private Map<String, Object> verification(UUID companyId) {
        return store.find(companyId).orElseThrow(() -> new AssertionError("No verification stored"));
    }

    private OffsetDateTime projectionReadAt(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT projection_read_at FROM cheque_projection_verifications WHERE company_id = ?",
                OffsetDateTime.class, companyId);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
