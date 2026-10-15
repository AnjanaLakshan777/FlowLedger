package com.flowledger.forecast.events;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one cheque event to the projection. The processed_events row and the projection change
 * commit together or not at all, so an event that fails part-way can simply be delivered again.
 */
@Service
public class ChequeEventProcessor {

    public enum Outcome {
        /** The projection now holds this event's version. */
        APPLIED,
        /** The projection already held this version or a newer one; nothing changed. */
        STALE,
        /** This event ID was processed before; nothing changed. */
        DUPLICATE
    }

    private final ChequeProjectionStore store;
    private final TransactionTemplate transactionTemplate;

    public ChequeEventProcessor(ChequeProjectionStore store, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws InvalidChequeEventException if the cheque is already projected for another company;
     *                                     the transaction is rolled back
     */
    public Outcome process(ChequeEvent event) {
        return transactionTemplate.execute(status -> {
            if (!store.markProcessed(event)) {
                return Outcome.DUPLICATE;
            }
            Outcome outcome = store.upsert(event) ? Outcome.APPLIED : unchanged(event);
            store.recordOutcome(event, outcome.name());
            return outcome;
        });
    }

    private Outcome unchanged(ChequeEvent event) {
        // The upsert locked the existing row, so this reads what it compared against.
        ChequeProjectionStore.Current current = store.find(event.chequeId())
                .orElseThrow(() -> new IllegalStateException("Cheque row missing after a conflicting upsert"));
        if (!current.companyId().equals(event.companyId())) {
            throw new InvalidChequeEventException("Cheque is already projected for another company");
        }
        return Outcome.STALE;
    }
}
