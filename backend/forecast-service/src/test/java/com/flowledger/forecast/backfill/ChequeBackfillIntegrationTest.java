package com.flowledger.forecast.backfill;

import com.flowledger.forecast.client.PaymentInternalClient;
import com.flowledger.forecast.config.PaymentServiceProperties;
import com.flowledger.forecast.events.ChequeEvent;
import com.flowledger.forecast.events.ChequeEventProcessor;
import com.flowledger.forecast.events.ChequeProjectionStore;
import com.flowledger.forecast.support.FakePaymentInternalApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * The cheque backfill against the local forecast PostgreSQL database and a fake payment-service
 * internal API. Not @Transactional: pages commit as in production. Events are applied through the
 * real ChequeEventProcessor (no broker needed). Test rows are deleted afterwards; nothing else is
 * changed.
 */
@SpringBootTest
class ChequeBackfillIntegrationTest {

    private static final FakePaymentInternalApi payment = FakePaymentInternalApi.instance();
    private static final int PAGE_SIZE = 3;
    private static final int MAX_ATTEMPTS = 5;

    @DynamicPropertySource
    static void paymentService(DynamicPropertyRegistry registry) {
        registry.add("flowledger.payment.base-url", payment::baseUrl);
        registry.add("flowledger.cheque-backfill.page-size", () -> PAGE_SIZE);
        registry.add("flowledger.cheque-backfill.max-attempts", () -> MAX_ATTEMPTS);
    }

    @Value("${flowledger.payment.internal-api-secret}")
    private String internalKey;

    @Autowired
    private ChequeBackfill backfill;

    @Autowired
    private ChequeEventProcessor eventProcessor;

    @Autowired
    private ChequeProjectionStore projectionStore;

    @Autowired
    private ChequeBackfillProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ChequeBackfillStore store;

    private final Set<UUID> companies = new HashSet<>();
    private final Set<UUID> events = new HashSet<>();
    private final List<UUID> sortedIds = new ArrayList<>();
    private Set<UUID> existingRuns;

    @BeforeEach
    void setUp() {
        payment.reset(internalKey);
        // A run in progress outside this test would be resumed by it; refuse instead of touching it.
        assertThat(store.findInProgress()).as("a backfill run left in progress").isEmpty();
        existingRuns = new HashSet<>(jdbcTemplate.queryForList("SELECT id FROM cheque_backfill_runs", UUID.class));
    }

    @AfterEach
    void deleteTestData() {
        // Only the runs this test created; earlier runs and their rejections are kept.
        for (UUID runId : newRuns()) {
            jdbcTemplate.update("DELETE FROM cheque_backfill_rejections WHERE backfill_run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM cheque_backfill_runs WHERE id = ?", runId);
        }
        for (UUID companyId : companies) {
            jdbcTemplate.update("DELETE FROM forecast_cheques WHERE company_id = ?", companyId);
        }
        for (UUID eventId : events) {
            jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
    }

    // Scan and checkpoints

    @Test
    void shouldBackfillEveryPageAndCompleteTheRun() {
        UUID companyId = company();
        seven(companyId);
        payment.cheque(sortedIds.get(4), companyId, "CANCELLED", 3).put("expectedClearingDate", "2099-12-31");

        ChequeBackfill.Result result = backfill.run();

        assertThat(result.outcome()).isEqualTo(ChequeBackfill.Outcome.COMPLETED);
        Map<String, Object> run = run(result.runId());
        assertThat(run).containsEntry("status", "COMPLETED").containsEntry("pages_processed", 3)
                .containsEntry("cheques_seen", 7L).containsEntry("applied_count", 7L)
                .containsEntry("stale_count", 0L).containsEntry("rejected_count", 0L)
                .containsEntry("failure_count", 0).containsEntry("cursor_after_id", sortedIds.getLast());
        assertThat(run.get("completed_at")).isNotNull();
        assertThat(payment.requestedAfterIds()).containsExactly("null", sortedIds.get(2).toString(),
                sortedIds.get(5).toString());

        Map<String, Object> row = projection(sortedIds.get(4));
        assertThat(row).containsEntry("company_id", companyId).containsEntry("status", "CANCELLED")
                .containsEntry("source_version", 3).containsEntry("projection_source", "BACKFILL")
                .containsEntry("expected_clearing_date", java.sql.Date.valueOf("2099-12-31"));
        assertThat(row.get("last_event_id")).isNull();
        assertThat(row.get("source_event_created_at")).isNull();
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1500.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM forecast_cheques WHERE company_id = ?",
                Integer.class, companyId)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE result_summary ->> 'chequeId' = ANY (?)", Integer.class,
                (Object) sortedIds.stream().map(UUID::toString).toArray(String[]::new))).isZero();
    }

    @Test
    void shouldStopAfterBoundedRetriesAndResumeFromTheCheckpoint() {
        UUID companyId = company();
        seven(companyId);
        payment.failPage(sortedIds.get(5), 100);

        ChequeBackfill.Result stopped = backfill.run();

        assertThat(stopped.outcome()).isEqualTo(ChequeBackfill.Outcome.STOPPED);
        Map<String, Object> run = run(stopped.runId());
        assertThat(run).containsEntry("status", "IN_PROGRESS").containsEntry("cursor_after_id", sortedIds.get(5))
                .containsEntry("pages_processed", 2).containsEntry("applied_count", 6L)
                .containsEntry("failure_count", MAX_ATTEMPTS)
                .containsEntry("last_error", "payment-service answered 503");
        assertThat(payment.requestedAfterIds()).filteredOn(sortedIds.get(5).toString()::equals).hasSize(MAX_ATTEMPTS);
        assertThat(findProjection(sortedIds.get(6))).isEmpty();

        payment.reset(internalKey);
        sevenAgain(companyId);
        ChequeBackfill.Result resumed = backfill.run();

        assertThat(resumed.runId()).isEqualTo(stopped.runId());
        assertThat(resumed.outcome()).isEqualTo(ChequeBackfill.Outcome.COMPLETED);
        // Pages already applied are not read again.
        assertThat(payment.requestedAfterIds()).containsExactly(sortedIds.get(5).toString());
        assertThat(run(resumed.runId())).containsEntry("cheques_seen", 7L).containsEntry("applied_count", 7L)
                .containsEntry("pages_processed", 3).containsEntry("status", "COMPLETED");
    }

    @Test
    void shouldRecoverFromTransientFailuresWithinOneRun() {
        UUID companyId = company();
        seven(companyId);
        payment.failPage(sortedIds.get(2), 2);
        payment.failureStatus(500);

        ChequeBackfill.Result result = backfill.run();

        assertThat(result.outcome()).isEqualTo(ChequeBackfill.Outcome.COMPLETED);
        assertThat(run(result.runId())).containsEntry("failure_count", 2).containsEntry("applied_count", 7L)
                .containsEntry("last_error", "payment-service answered 500");
    }

    @Test
    void shouldRollBackAPageWhoseTransactionFailsAndApplyItOnce() {
        UUID companyId = company();
        seven(companyId);
        doThrow(new TransientDataAccessResourceException("connection lost"))
                .doCallRealMethod()
                .when(store).advance(any(), any(), anyLong(), anyLong(), anyLong(), anyBoolean());

        ChequeBackfill.Result result = backfill.run();

        // Had the failed attempt's upserts survived, the retry would count them as stale.
        assertThat(run(result.runId())).containsEntry("applied_count", 7L).containsEntry("stale_count", 0L)
                .containsEntry("cheques_seen", 7L).containsEntry("failure_count", 1)
                .containsEntry("last_error", "TransientDataAccessResourceException");
    }

    @Test
    void shouldLeaveNothingFromAPageThatNeverCommits() {
        UUID companyId = company();
        seven(companyId);
        doThrow(new TransientDataAccessResourceException("connection lost"))
                .when(store).advance(any(), any(), anyLong(), anyLong(), anyLong(), anyBoolean());

        ChequeBackfill.Result result = backfill.run();

        assertThat(result.outcome()).isEqualTo(ChequeBackfill.Outcome.STOPPED);
        assertThat(run(result.runId())).containsEntry("pages_processed", 0).containsEntry("cheques_seen", 0L)
                .containsEntry("failure_count", MAX_ATTEMPTS);
        assertThat(run(result.runId()).get("cursor_after_id")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM forecast_cheques WHERE company_id = ?",
                Integer.class, companyId)).isZero();
    }

    @Test
    void shouldChangeNothingWhenRunAgain() {
        UUID companyId = company();
        seven(companyId);
        UUID first = backfill.run().runId();
        Object projectedAt = projection(sortedIds.getFirst()).get("projected_at");

        ChequeBackfill.Result again = backfill.run();

        assertThat(again.runId()).isNotEqualTo(first);
        assertThat(run(again.runId())).containsEntry("applied_count", 0L).containsEntry("stale_count", 7L)
                .containsEntry("status", "COMPLETED");
        assertThat(projection(sortedIds.getFirst()).get("projected_at")).isEqualTo(projectedAt);
    }

    @Test
    void shouldShareOneRunBetweenConcurrentWorkers() throws Exception {
        UUID companyId = company();
        seven(companyId);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<ChequeBackfill.Result> results = new ArrayList<>();
        try {
            Callable<ChequeBackfill.Result> worker = () -> {
                start.await(10, TimeUnit.SECONDS);
                return backfill.run();
            };
            List<Future<ChequeBackfill.Result>> futures = List.of(executor.submit(worker), executor.submit(worker));
            for (Future<ChequeBackfill.Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(results).extracting(ChequeBackfill.Result::runId).containsOnly(results.getFirst().runId());
        assertThat(results).extracting(ChequeBackfill.Result::outcome).containsOnly(ChequeBackfill.Outcome.COMPLETED);
        assertThat(run(results.getFirst().runId())).containsEntry("cheques_seen", 7L)
                .containsEntry("applied_count", 7L).containsEntry("pages_processed", 3);
        assertThat(newRuns()).hasSize(1);
    }

    // Version conflicts with events

    @Test
    void shouldKeepANewerEventVersion() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        ChequeEvent event = event(companyId, chequeId, 3, "2750.00");
        eventProcessor.process(event);
        payment.cheque(chequeId, companyId, "ISSUED", 2);

        ChequeBackfill.Result result = backfill.run();

        assertThat(run(result.runId())).containsEntry("stale_count", 1L).containsEntry("applied_count", 0L);
        Map<String, Object> row = projection(chequeId);
        assertThat(row).containsEntry("source_version", 3).containsEntry("projection_source", "EVENT")
                .containsEntry("last_event_id", event.messageId());
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("2750.00");
    }

    @Test
    void shouldReplaceAnOlderEventVersion() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        eventProcessor.process(event(companyId, chequeId, 1, "2750.00"));
        payment.cheque(chequeId, companyId, "CANCELLED", 2);

        backfill.run();

        Map<String, Object> row = projection(chequeId);
        assertThat(row).containsEntry("source_version", 2).containsEntry("status", "CANCELLED")
                .containsEntry("projection_source", "BACKFILL");
        assertThat(row.get("last_event_id")).isNull();
        assertThat(row.get("source_event_created_at")).isNull();
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1500.00");
    }

    @Test
    void shouldLetLaterEventsWinOnlyWithAHigherVersion() {
        UUID companyId = company();
        UUID chequeId = UUID.randomUUID();
        payment.cheque(chequeId, companyId, "ISSUED", 2);
        backfill.run();

        assertThat(eventProcessor.process(event(companyId, chequeId, 1, "10.00")))
                .isEqualTo(ChequeEventProcessor.Outcome.STALE);
        assertThat(eventProcessor.process(event(companyId, chequeId, 2, "20.00")))
                .isEqualTo(ChequeEventProcessor.Outcome.STALE);
        assertThat(projection(chequeId)).containsEntry("projection_source", "BACKFILL")
                .containsEntry("source_version", 2);

        ChequeEvent newer = event(companyId, chequeId, 3, "30.00");
        assertThat(eventProcessor.process(newer)).isEqualTo(ChequeEventProcessor.Outcome.APPLIED);
        assertThat(projection(chequeId)).containsEntry("projection_source", "EVENT")
                .containsEntry("last_event_id", newer.messageId()).containsEntry("source_version", 3);
    }

    @Test
    void shouldNotOverwriteAnEventAppliedBetweenReadingAndApplyingAPage() {
        UUID companyId = company();
        seven(companyId);
        UUID raced = sortedIds.get(4);
        ChequeEvent newer = event(companyId, raced, 9, "9999.00");
        // The second page is read with version 1 of this cheque; the event lands before it is applied.
        payment.beforeResponse(afterId -> {
            if (afterId.equals(sortedIds.get(2).toString())) {
                eventProcessor.process(newer);
            }
        });

        ChequeBackfill.Result result = backfill.run();

        assertThat(run(result.runId())).containsEntry("applied_count", 6L).containsEntry("stale_count", 1L);
        assertThat(projection(raced)).containsEntry("source_version", 9).containsEntry("projection_source", "EVENT")
                .containsEntry("last_event_id", newer.messageId());
    }

    // Rejected items

    @Test
    void shouldRejectAChequeAlreadyProjectedForAnotherCompany() {
        UUID owner = company();
        UUID other = company();
        UUID chequeId = UUID.randomUUID();
        eventProcessor.process(event(owner, chequeId, 1, "2750.00"));
        payment.cheque(chequeId, other, "ISSUED", 5);
        UUID fine = UUID.randomUUID();
        payment.cheque(fine, other, "ISSUED", 1);

        ChequeBackfill.Result result = backfill.run();

        assertThat(result.outcome()).isEqualTo(ChequeBackfill.Outcome.COMPLETED);
        assertThat(run(result.runId())).containsEntry("rejected_count", 1L).containsEntry("applied_count", 1L);
        assertThat(projection(chequeId)).containsEntry("company_id", owner).containsEntry("source_version", 1);
        assertThat(rejections(result.runId())).singleElement().satisfies(rejection -> assertThat(rejection)
                .containsEntry("cheque_id", chequeId).containsEntry("company_id", other)
                .containsEntry("reason", "Cheque is already projected for another company"));
    }

    @Test
    void shouldRecordInvalidItemsWithoutTheirValuesAndContinue() {
        UUID companyId = company();
        UUID invalid = UUID.randomUUID();
        payment.cheque(invalid, companyId, "ISSUED", 1).put("amount", "-98765.43");
        payment.cheque(UUID.randomUUID(), companyId, "PAID", 1);
        UUID valid = UUID.randomUUID();
        Map<String, Object> item = payment.cheque(valid, companyId, "ISSUED", 1);
        item.put("payeeName", "Secret Payee Holdings");
        item.put("notes", "private settlement note");

        ChequeBackfill.Result result = backfill.run();

        assertThat(run(result.runId())).containsEntry("rejected_count", 2L).containsEntry("applied_count", 1L)
                .containsEntry("status", "COMPLETED");
        assertThat(rejections(result.runId())).extracting(r -> r.get("reason"))
                .containsExactlyInAnyOrder("Invalid amount", "Invalid status");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT string_agg(r::text, ' ') FROM cheque_backfill_rejections r WHERE backfill_run_id = ?",
                String.class, result.runId()))
                .doesNotContain("98765", "PAID", "Secret", "private");
        assertThat(findProjection(invalid)).isEmpty();
        assertThat(findProjection(valid)).isPresent();
    }

    // Failures that must not move the cursor

    @Test
    void shouldNotMoveTheCursorPastAPageThatBreaksTheContract() {
        UUID companyId = company();
        seven(companyId);
        payment.rewritePages(page -> {
            List<Map<String, Object>> items = new ArrayList<>((List<Map<String, Object>>) page.get("items"));
            java.util.Collections.reverse(items);
            page.put("items", items);
            return page;
        });

        ChequeBackfill.Result result = backfill.run();

        assertThat(result.outcome()).isEqualTo(ChequeBackfill.Outcome.STOPPED);
        assertThat(run(result.runId())).containsEntry("pages_processed", 0)
                .containsEntry("last_error", "Invalid scan page: items out of order");
        assertThat(run(result.runId()).get("cursor_after_id")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM forecast_cheques WHERE company_id = ?",
                Integer.class, companyId)).isZero();
    }

    @Test
    void shouldStopWhenPaymentServiceRefusesTheKey() {
        seven(company());
        payment.reset("another-key-that-is-not-configured-here-0000000000");

        ChequeBackfill.Result result = backfill.run();

        assertThat(result.outcome()).isEqualTo(ChequeBackfill.Outcome.STOPPED);
        assertThat(run(result.runId())).containsEntry("last_error", "payment-service answered 401");
    }

    @Test
    void shouldRefuseToStartWithoutTheKey() {
        PaymentInternalClient unconfigured = new PaymentInternalClient(RestClient.create(),
                new PaymentServiceProperties(payment.baseUrl(), null, null, ""));
        ChequeBackfill withoutKey = new ChequeBackfill(unconfigured, store, projectionStore, properties,
                transactionManager);

        assertThatThrownBy(withoutKey::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_INTERNAL_API_SECRET");
        assertThat(newRuns()).isEmpty();
        assertThat(payment.requestedAfterIds()).isEmpty();
    }

    // Helpers

    private UUID company() {
        UUID companyId = UUID.randomUUID();
        companies.add(companyId);
        return companyId;
    }

    // Seven cheques of the company in the fake scan; sortedIds holds them in scan order.
    private void seven(UUID companyId) {
        sortedIds.clear();
        for (int i = 0; i < 7; i++) {
            sortedIds.add(UUID.randomUUID());
        }
        sortedIds.sort(PaymentInternalClient::compareInScanOrder);
        sevenAgain(companyId);
    }

    private void sevenAgain(UUID companyId) {
        sortedIds.forEach(id -> payment.cheque(id, companyId, "ISSUED", 1));
    }

    private ChequeEvent event(UUID companyId, UUID chequeId, int version, String amount) {
        UUID eventId = UUID.randomUUID();
        events.add(eventId);
        return new ChequeEvent(eventId, "ChequeUpdated", companyId, chequeId,
                UUID.fromString("5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f"), "000123", "SUPPLIER_PAYMENT",
                new BigDecimal(amount), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 22), "ISSUED", version,
                Instant.parse("2026-10-15T08:30:00Z"));
    }

    private List<UUID> newRuns() {
        return jdbcTemplate.queryForList("SELECT id FROM cheque_backfill_runs", UUID.class).stream()
                .filter(id -> !existingRuns.contains(id)).toList();
    }

    private Map<String, Object> run(UUID runId) {
        return jdbcTemplate.queryForMap("SELECT * FROM cheque_backfill_runs WHERE id = ?", runId);
    }

    private List<Map<String, Object>> rejections(UUID runId) {
        return jdbcTemplate.queryForList("SELECT * FROM cheque_backfill_rejections WHERE backfill_run_id = ?", runId);
    }

    private Map<String, Object> projection(UUID chequeId) {
        return findProjection(chequeId).orElseThrow(() -> new AssertionError("Cheque not projected"));
    }

    private java.util.Optional<Map<String, Object>> findProjection(UUID chequeId) {
        return jdbcTemplate.queryForList("SELECT * FROM forecast_cheques WHERE cheque_id = ?", chequeId)
                .stream().findFirst();
    }
}
