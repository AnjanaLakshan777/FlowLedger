package com.flowledger.forecast.verification;

import com.flowledger.forecast.client.PaymentInternalClient;
import com.flowledger.forecast.client.PaymentInternalClient.CompanyChequeSummary;
import com.flowledger.forecast.verification.ChequeVerificationStore.BackfillEvidence;
import com.flowledger.forecast.verification.ChequeVerificationStore.Figures;
import com.flowledger.forecast.verification.ChequeVerificationStore.Projection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Checks, per company, whether forecast_cheques matches payment-service's cheques, using only
 * payment-service's internal summary API and forecast's own data. The order matters: the
 * dead-letter queue and backfill evidence first, then the projection in one statement, then the
 * summary. Cheques are never deleted and versions only grow, so the projection read first can
 * only be behind the later summary, never ahead; equal figures mean the projection held
 * payment-service's state at the summary time (see docs/decisions.md for the assumptions). Any
 * gap or missing evidence gives RETRY_REQUIRED or NOT_VERIFIED, never VERIFIED.
 * <p>
 * The result is stored per company and is not used by forecast calculations yet. Only company
 * IDs, figures and reason codes are logged.
 */
@Slf4j
@Service
public class ChequeProjectionVerifier {

    public enum Status { VERIFIED, RETRY_REQUIRED, NOT_VERIFIED }

    public enum Reason {
        MATCHED(Status.VERIFIED),
        /** No backfill run has completed, so cheques from before the outbox boundary may be missing. */
        NO_COMPLETED_BACKFILL(Status.NOT_VERIFIED),
        /** The latest completed or a running backfill rejected cheques of this (or an unknown) company. */
        BACKFILL_REJECTIONS(Status.NOT_VERIFIED),
        /** Cheque events wait in the dead-letter queue; their company is not known here. */
        DEAD_LETTERS_PRESENT(Status.NOT_VERIFIED),
        DEAD_LETTER_EVIDENCE_UNAVAILABLE(Status.RETRY_REQUIRED),
        SUMMARY_UNAVAILABLE(Status.RETRY_REQUIRED),
        /** Fewer cheques or lower versions than payment-service: lag, a concurrent change, or a gap. */
        PROJECTION_BEHIND(Status.RETRY_REQUIRED),
        /** More cheques or higher versions than payment-service: impossible unless an assumption broke. */
        PROJECTION_AHEAD(Status.NOT_VERIFIED),
        /** Same cheques and versions but different ISSUED/CANCELLED split: an assumption broke. */
        STATUS_COUNTS_DIFFER(Status.NOT_VERIFIED);

        private final Status status;

        Reason(Status status) {
            this.status = status;
        }

        public Status status() {
            return status;
        }
    }

    public record CompanyResult(UUID companyId, Status status, Reason reason, Figures projected, Figures payment) {
    }

    /** Waits between checks; replaceable so tests do not sleep. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record Evidence(OptionalLong deadLetters, BackfillEvidence backfill, Projection projection,
                            Map<UUID, CompanyChequeSummary> summary) {
    }

    private final PaymentInternalClient paymentClient;
    private final ChequeVerificationStore store;
    private final DeadLetterEvidence deadLetterEvidence;
    private final ChequeVerificationProperties properties;
    private final Sleeper sleeper;

    @Autowired
    public ChequeProjectionVerifier(PaymentInternalClient paymentClient, ChequeVerificationStore store,
                                    DeadLetterEvidence deadLetterEvidence, ChequeVerificationProperties properties) {
        this(paymentClient, store, deadLetterEvidence, properties, duration -> Thread.sleep(duration));
    }

    ChequeProjectionVerifier(PaymentInternalClient paymentClient, ChequeVerificationStore store,
                             DeadLetterEvidence deadLetterEvidence, ChequeVerificationProperties properties,
                             Sleeper sleeper) {
        this.paymentClient = paymentClient;
        this.store = store;
        this.deadLetterEvidence = deadLetterEvidence;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    /**
     * Checks every company that has cheques in payment-service or in the projection. Companies
     * that come out RETRY_REQUIRED are checked again, up to the configured attempts.
     */
    public List<CompanyResult> verifyAll() {
        return verify(null, properties.maxAttempts());
    }

    /**
     * Checks one company with payment-service's summary for that company only. A company without
     * cheques on either side verifies with zero figures.
     */
    public CompanyResult verifyCompany(UUID companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required");
        }
        return verify(companyId, properties.maxAttempts()).getFirst();
    }

    /**
     * Checks one company once, without waiting to retry, for a request that needs the answer now.
     * The result is stored like any other.
     */
    public CompanyResult verifyCompanyNow(UUID companyId) {
        if (companyId == null) {
            throw new IllegalArgumentException("companyId is required");
        }
        return verify(companyId, 1).getFirst();
    }

    private List<CompanyResult> verify(UUID onlyCompany, int maxAttempts) {
        List<CompanyResult> finished = new ArrayList<>();
        Set<UUID> pending = null;
        for (int attempt = 1; ; attempt++) {
            Evidence evidence = gather(onlyCompany);
            Set<UUID> companies = new LinkedHashSet<>();
            if (onlyCompany != null) {
                companies.add(onlyCompany);
            } else {
                companies.addAll(evidence.projection().companies().keySet());
                if (evidence.summary() != null) {
                    companies.addAll(evidence.summary().keySet());
                }
            }
            if (pending != null) {
                companies.retainAll(pending);
            }

            Set<UUID> retry = new LinkedHashSet<>();
            for (UUID companyId : companies) {
                CompanyResult result = decide(companyId, evidence);
                store.save(new ChequeVerificationStore.Result(companyId, result.status().name(),
                        result.reason().name(), evidence.backfill().completedRunId(), result.projected(),
                        result.payment(), evidence.deadLetters().isPresent()
                        ? evidence.deadLetters().getAsLong() : null, evidence.projection().readAt()));
                if (result.status() == Status.RETRY_REQUIRED && attempt < maxAttempts) {
                    retry.add(companyId);
                } else {
                    finished.add(result);
                }
            }
            if (retry.isEmpty()) {
                log.info("Cheque projection verification: {} companies, {} verified", finished.size(),
                        finished.stream().filter(r -> r.status() == Status.VERIFIED).count());
                return finished;
            }
            log.info("Cheque projection verification: {} companies need another check (attempt {})",
                    retry.size(), attempt);
            pending = retry;
            try {
                sleeper.sleep(properties.retryDelay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // The stored results stay RETRY_REQUIRED.
                return finished;
            }
        }
    }

    private Evidence gather(UUID onlyCompany) {
        OptionalLong deadLetters = deadLetterEvidence.deadLetterCount();
        BackfillEvidence backfill = store.backfillEvidence();
        Projection projection = store.projection(onlyCompany);
        Map<UUID, CompanyChequeSummary> summary;
        try {
            summary = paymentClient.fetchSummary(onlyCompany);
        } catch (PaymentInternalClient.ScanFailedException e) {
            log.warn("Cheque summary from payment-service unavailable: {}", e.getMessage());
            summary = null;
        }
        return new Evidence(deadLetters, backfill, projection, summary);
    }

    // The first problem found decides; only a clean comparison verifies.
    private static CompanyResult decide(UUID companyId, Evidence evidence) {
        Figures projected = evidence.projection().companies().getOrDefault(companyId, Figures.NONE);
        Figures payment = null;
        if (evidence.summary() != null) {
            CompanyChequeSummary summary = evidence.summary().get(companyId);
            payment = summary == null ? Figures.NONE : new Figures(summary.chequeCount(), summary.issuedCount(),
                    summary.cancelledCount(), summary.versionSum());
        }
        Reason reason = reason(companyId, evidence, projected, payment);
        return new CompanyResult(companyId, reason.status(), reason, projected, payment);
    }

    private static Reason reason(UUID companyId, Evidence evidence, Figures projected, Figures payment) {
        BackfillEvidence backfill = evidence.backfill();
        if (backfill.completedRunId() == null) {
            return Reason.NO_COMPLETED_BACKFILL;
        }
        if (backfill.rejectedUnknown() || backfill.rejectedCompanies().contains(companyId)) {
            return Reason.BACKFILL_REJECTIONS;
        }
        if (evidence.deadLetters().isEmpty()) {
            return Reason.DEAD_LETTER_EVIDENCE_UNAVAILABLE;
        }
        if (evidence.deadLetters().getAsLong() > 0) {
            return Reason.DEAD_LETTERS_PRESENT;
        }
        if (payment == null) {
            return Reason.SUMMARY_UNAVAILABLE;
        }
        if (projected.chequeCount() > payment.chequeCount() || projected.versionSum() > payment.versionSum()) {
            return Reason.PROJECTION_AHEAD;
        }
        if (projected.chequeCount() < payment.chequeCount() || projected.versionSum() < payment.versionSum()) {
            return Reason.PROJECTION_BEHIND;
        }
        if (projected.issuedCount() != payment.issuedCount() || projected.cancelledCount() != payment.cancelledCount()) {
            return Reason.STATUS_COUNTS_DIFFER;
        }
        return Reason.MATCHED;
    }
}
