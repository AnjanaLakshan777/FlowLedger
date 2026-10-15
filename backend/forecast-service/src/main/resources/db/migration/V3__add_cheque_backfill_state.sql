-- State for filling forecast_cheques from payment-service's internal cheque scan (backfill), for
-- cheques whose events were never published. Only additions and one relaxed constraint; existing
-- rows are unchanged. Saved forecast runs are not touched.

-- A backfilled row has no event behind it. Event rows keep their event ID; the check below keeps
-- source and event ID consistent. Existing rows all came from events and read 'EVENT'.
ALTER TABLE forecast_cheques
    ALTER COLUMN last_event_id DROP NOT NULL;

ALTER TABLE forecast_cheques
    ADD COLUMN projection_source VARCHAR(16) NOT NULL DEFAULT 'EVENT'
        CHECK (projection_source IN ('EVENT', 'BACKFILL'));

ALTER TABLE forecast_cheques
    ADD CONSTRAINT ck_forecast_cheques_event_id_matches_source
        CHECK ((projection_source = 'EVENT') = (last_event_id IS NOT NULL));

-- One row per backfill pass over the scan. cursor_after_id is the last cheque ID whose page has
-- been applied; the counters are updated in the same transaction as the cursor, so a resumed run
-- neither skips nor double-counts a page. A completed run says only that the scan reached its
-- end; it does not mean the projection is verified complete.
CREATE TABLE cheque_backfill_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    cursor_after_id UUID,
    pages_processed INTEGER NOT NULL DEFAULT 0
        CHECK (pages_processed >= 0),
    cheques_seen BIGINT NOT NULL DEFAULT 0,
    applied_count BIGINT NOT NULL DEFAULT 0
        CHECK (applied_count >= 0),
    stale_count BIGINT NOT NULL DEFAULT 0
        CHECK (stale_count >= 0),
    rejected_count BIGINT NOT NULL DEFAULT 0
        CHECK (rejected_count >= 0),
    -- Failed attempts (fetch or apply) over the run's life, and the latest one's short reason:
    -- an exception class or HTTP status, never response bodies or secrets.
    failure_count INTEGER NOT NULL DEFAULT 0
        CHECK (failure_count >= 0),
    last_error VARCHAR(300),
    last_failed_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_cheque_backfill_runs_completed_at
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_cheque_backfill_runs_counts
        CHECK (cheques_seen = applied_count + stale_count + rejected_count)
);

-- At most one run in progress, so two workers resume the same run instead of starting two.
CREATE UNIQUE INDEX uq_cheque_backfill_runs_one_in_progress
    ON cheque_backfill_runs (status)
    WHERE status = 'IN_PROGRESS';

-- Scan items that were not applied: invalid fields, or a cheque already projected for another
-- company. IDs and a reason naming the problem only, never field values.
CREATE TABLE cheque_backfill_rejections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    backfill_run_id UUID NOT NULL,
    cheque_id UUID,
    company_id UUID,
    reason VARCHAR(200) NOT NULL,
    rejected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cheque_backfill_rejections_run
        FOREIGN KEY (backfill_run_id) REFERENCES cheque_backfill_runs (id) ON DELETE RESTRICT
);

CREATE INDEX idx_cheque_backfill_rejections_run
    ON cheque_backfill_rejections (backfill_run_id);
