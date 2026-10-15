-- Activation boundary for outbox publishing. Rows written before this migration were never meant
-- to be published (development and test data), so they must stay untouched and unpublished.
--
-- Adding a column with a constant default is a catalogue-only change in PostgreSQL 11+: existing
-- rows are not rewritten and read the default, false. Switching the default to true afterwards
-- applies to new inserts only, so every event written from now on is publishable without any
-- change to the code that writes it. ALTER TABLE waits for in-flight transactions, so the boundary
-- follows commit order rather than clocks (created_at is a transaction start time).
ALTER TABLE outbox_events ADD COLUMN publishable BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE outbox_events ALTER COLUMN publishable SET DEFAULT true;

-- The publisher claims pending events that are due, oldest first.
CREATE INDEX idx_outbox_events_pending
    ON outbox_events (next_attempt_at, created_at)
    WHERE publishable AND published_at IS NULL;
