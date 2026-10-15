-- Latest completeness check of the cheque projection, per company. Separate from the backfill
-- runs: a COMPLETED run only means the scan ended; VERIFIED here means the projection matched
-- payment-service's summary for that company when it was checked, with no known gaps. Additive
-- only; nothing existing changes.

CREATE TABLE cheque_projection_verifications (
    -- Company managed by identity-service; stored as an ID only.
    company_id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('VERIFIED', 'RETRY_REQUIRED', 'NOT_VERIFIED')),
    -- Why: MATCHED for VERIFIED, otherwise the first problem found (see docs/decisions.md).
    reason VARCHAR(40) NOT NULL
        CHECK (reason IN ('MATCHED', 'NO_COMPLETED_BACKFILL', 'BACKFILL_REJECTIONS', 'DEAD_LETTERS_PRESENT',
                          'DEAD_LETTER_EVIDENCE_UNAVAILABLE', 'SUMMARY_UNAVAILABLE', 'PROJECTION_BEHIND',
                          'PROJECTION_AHEAD', 'STATUS_COUNTS_DIFFER')),
    -- The completed backfill run the check relied on, if any.
    backfill_run_id UUID,
    -- The projection's figures, read before payment-service's summary.
    projected_cheque_count BIGINT NOT NULL CHECK (projected_cheque_count >= 0),
    projected_issued_count BIGINT NOT NULL CHECK (projected_issued_count >= 0),
    projected_cancelled_count BIGINT NOT NULL CHECK (projected_cancelled_count >= 0),
    projected_version_sum BIGINT NOT NULL CHECK (projected_version_sum >= 0),
    -- payment-service's summary for the company; null when it could not be read.
    payment_cheque_count BIGINT,
    payment_issued_count BIGINT,
    payment_cancelled_count BIGINT,
    payment_version_sum BIGINT,
    -- Ready messages in the cheque-event dead-letter queue; null when it could not be read.
    dead_letter_count BIGINT,
    -- Database time just before the projection was read. A check never overwrites a result
    -- whose projection was read later.
    projection_read_at TIMESTAMPTZ NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    check_count INTEGER NOT NULL DEFAULT 1 CHECK (check_count > 0),
    CONSTRAINT fk_cheque_projection_verifications_run
        FOREIGN KEY (backfill_run_id) REFERENCES cheque_backfill_runs (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cheque_projection_verifications_verified
        CHECK ((status = 'VERIFIED') = (reason = 'MATCHED')
            AND (status = 'VERIFIED') = (verified_at IS NOT NULL)),
    CONSTRAINT ck_cheque_projection_verifications_payment_figures
        CHECK ((payment_cheque_count IS NULL) = (payment_version_sum IS NULL)
            AND (payment_cheque_count IS NULL) = (payment_issued_count IS NULL)
            AND (payment_cheque_count IS NULL) = (payment_cancelled_count IS NULL)),
    -- Verification needs payment-service's figures, a completed backfill run and an empty
    -- dead-letter queue.
    CONSTRAINT ck_cheque_projection_verifications_verified_evidence
        CHECK (status <> 'VERIFIED' OR (payment_cheque_count IS NOT NULL AND backfill_run_id IS NOT NULL
            AND dead_letter_count = 0))
);

CREATE INDEX idx_cheque_projection_verifications_status
    ON cheque_projection_verifications (status);
