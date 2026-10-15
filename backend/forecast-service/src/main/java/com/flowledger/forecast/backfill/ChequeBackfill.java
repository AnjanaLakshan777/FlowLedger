package com.flowledger.forecast.backfill;

import com.flowledger.forecast.client.PaymentInternalClient;
import com.flowledger.forecast.client.PaymentInternalClient.ScanPage;
import com.flowledger.forecast.events.ChequeFields;
import com.flowledger.forecast.events.ChequeProjectionStore;
import com.flowledger.forecast.events.InvalidChequeEventException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Fills forecast_cheques from payment-service's internal cheque scan, page by page:
 * <ul>
 *     <li>the page is read over HTTP outside any transaction;</li>
 *     <li>one transaction then locks the run, checks that its cursor has not moved (another worker
 *     may have applied the page), applies every item with the projection's version guard, records
 *     rejected items, and moves the cursor and counters;</li>
 *     <li>a failed read or transaction is retried with backoff, from the same cursor, up to a limit;
 *     the run then stays in progress and the next pass resumes it.</li>
 * </ul>
 * A cheque is never moved to an older or equal version, whether events or the backfill wrote it,
 * and never moved to another company. The backfill does not write processed_events. Only run IDs
 * and counts are logged.
 */
@Slf4j
@Service
public class ChequeBackfill {

    public enum Outcome {
        /** The scan reached its end. Not a verification of the projection. */
        COMPLETED,
        /** A page kept failing; the run is still in progress and resumes from its cursor. */
        STOPPED
    }

    public record Result(UUID runId, Outcome outcome) {
    }

    /** Waits between attempts; replaceable so tests do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final PaymentInternalClient client;
    private final ChequeBackfillStore store;
    private final ChequeProjectionStore projection;
    private final ChequeBackfillProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Sleeper sleeper;

    @Autowired
    public ChequeBackfill(PaymentInternalClient client, ChequeBackfillStore store, ChequeProjectionStore projection,
                          ChequeBackfillProperties properties, PlatformTransactionManager transactionManager) {
        this(client, store, projection, properties, transactionManager, duration -> Thread.sleep(duration));
    }

    ChequeBackfill(PaymentInternalClient client, ChequeBackfillStore store, ChequeProjectionStore projection,
                   ChequeBackfillProperties properties, PlatformTransactionManager transactionManager,
                   Sleeper sleeper) {
        this.client = client;
        this.store = store;
        this.projection = projection;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.sleeper = sleeper;
    }

    /**
     * Starts a run, or resumes the one in progress, and works through the scan until it ends or a
     * page keeps failing.
     *
     * @throws IllegalStateException if PAYMENT_INTERNAL_API_SECRET is not configured
     */
    public Result run() {
        if (!client.configured()) {
            throw new IllegalStateException("PAYMENT_INTERNAL_API_SECRET is not set; the cheque backfill cannot "
                    + "read payment-service.");
        }
        UUID runId = store.startOrResume().id();
        log.info("Cheque backfill run {} started or resumed", runId);

        int attempt = 0;
        Duration backoff = properties.initialBackoff();
        while (true) {
            ChequeBackfillStore.RunState read;
            try {
                read = store.load(runId);
                if (read.completed()) {
                    log.info("Cheque backfill run {} completed: {} cheques, {} applied, {} stale, {} rejected",
                            runId, read.chequesSeen(), read.appliedCount(), read.staleCount(), read.rejectedCount());
                    return new Result(runId, Outcome.COMPLETED);
                }
                ScanPage page = client.fetchPage(read.cursorAfterId(), properties.pageSize());
                applyPage(read, page);
                attempt = 0;
                backoff = properties.initialBackoff();
                continue;
            } catch (RuntimeException e) {
                attempt++;
                String reason = reason(e);
                recordFailure(runId, reason);
                if (attempt >= properties.maxAttempts()) {
                    log.warn("Cheque backfill run {} stopped after {} failed attempts on one page: {}",
                            runId, attempt, reason);
                    return new Result(runId, Outcome.STOPPED);
                }
                log.warn("Cheque backfill run {} attempt {} failed, retrying in {} ms: {}",
                        runId, attempt, backoff.toMillis(), reason);
            }
            try {
                sleeper.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Cheque backfill run {} interrupted; it resumes from its cursor next time", runId);
                return new Result(runId, Outcome.STOPPED);
            }
            backoff = min(backoff.multipliedBy(2), properties.maxBackoff());
        }
    }

    private void applyPage(ChequeBackfillStore.RunState read, ScanPage page) {
        transactionTemplate.executeWithoutResult(status -> {
            ChequeBackfillStore.RunState locked = store.lock(read.id());
            if (locked.completed() || !Objects.equals(locked.cursorAfterId(), read.cursorAfterId())) {
                // Another worker applied this page meanwhile; read the next one from its cursor.
                return;
            }
            long applied = 0;
            long stale = 0;
            long rejected = 0;
            for (Map<String, Object> item : page.items()) {
                switch (apply(read.id(), item)) {
                    case APPLIED -> applied++;
                    case STALE -> stale++;
                    case REJECTED -> rejected++;
                }
            }
            UUID cursor = page.items().isEmpty() ? read.cursorAfterId() : page.lastChequeId();
            store.advance(read.id(), cursor, applied, stale, rejected, page.nextAfterId() == null);
        });
    }

    private enum ItemOutcome { APPLIED, STALE, REJECTED }

    private ItemOutcome apply(UUID runId, Map<String, Object> item) {
        ChequeFields cheque;
        try {
            cheque = ChequeFields.from(item);
        } catch (InvalidChequeEventException e) {
            store.recordRejection(runId, idOrNull(item, "chequeId"), idOrNull(item, "companyId"), e.getMessage());
            return ItemOutcome.REJECTED;
        }
        if (projection.upsertSnapshot(cheque)) {
            return ItemOutcome.APPLIED;
        }
        // The upsert locked the existing row: same company means it already holds this version or a
        // newer one; another company means the IDs conflict and nothing may change.
        ChequeProjectionStore.Current current = projection.find(cheque.chequeId())
                .orElseThrow(() -> new IllegalStateException("Cheque row missing after a conflicting upsert"));
        if (!current.companyId().equals(cheque.companyId())) {
            store.recordRejection(runId, cheque.chequeId(), cheque.companyId(),
                    "Cheque is already projected for another company");
            return ItemOutcome.REJECTED;
        }
        return ItemOutcome.STALE;
    }

    private void recordFailure(UUID runId, String reason) {
        try {
            store.recordFailure(runId, reason);
        } catch (RuntimeException e) {
            log.warn("Could not record the failure of cheque backfill run {}: {}", runId, e.getClass().getSimpleName());
        }
    }

    // Safe to store and log: the client's own messages, otherwise only the exception class, because
    // database messages can quote values.
    private static String reason(RuntimeException e) {
        return e instanceof PaymentInternalClient.ScanFailedException ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static UUID idOrNull(Map<String, Object> item, String field) {
        try {
            return item.get(field) instanceof String text ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
