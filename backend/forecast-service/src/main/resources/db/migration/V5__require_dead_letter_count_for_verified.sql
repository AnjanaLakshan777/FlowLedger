-- V4's evidence check accepted a VERIFIED row without a dead-letter count: "dead_letter_count = 0"
-- is NULL, not false, when the count is missing, and a CHECK only rejects false. Replaced with a
-- check that requires the count to be known and zero. V4 stays unchanged.

ALTER TABLE cheque_projection_verifications
    DROP CONSTRAINT ck_cheque_projection_verifications_verified_evidence;

ALTER TABLE cheque_projection_verifications
    ADD CONSTRAINT ck_cheque_projection_verifications_verified_evidence
        CHECK (status <> 'VERIFIED' OR (payment_cheque_count IS NOT NULL AND backfill_run_id IS NOT NULL
            AND dead_letter_count IS NOT NULL AND dead_letter_count = 0));
