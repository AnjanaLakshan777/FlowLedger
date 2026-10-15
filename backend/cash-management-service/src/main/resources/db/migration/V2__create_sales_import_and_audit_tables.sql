-- Historical sales import (schema document sections 4.2-4.4) and the service's audit trail.
-- Additive only: daily_sales gains the import link and the HISTORICAL_IMPORT entry source; its
-- existing rows (all manual entry) are unchanged. See docs/decisions.md, section 8.

-- One uploaded file. Statuses (a deliberate simplification of the schema document's list, as
-- validation runs synchronously during the upload):
--   INVALID           the file cannot be approved: unreadable or wrong layout, no valid row, or
--                     a duplicate date (within the file or against recorded sales)
--   PENDING_APPROVAL  waiting for a Finance Controller; may contain invalid rows, which approval
--                     must explicitly leave out
--   APPROVED          its valid rows were recorded as daily sales in one transaction
--   REJECTED          a Finance Controller refused it, with a reason
CREATE TABLE sales_import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Company managed by identity-service; stored as an ID only, no cross-service FK.
    company_id UUID NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    -- SHA-256 of the uploaded bytes; the file itself is not stored.
    file_sha256 CHAR(64) NOT NULL
        CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    file_size_bytes INTEGER NOT NULL
        CHECK (file_size_bytes >= 0),
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'INVALID')),
    -- Why the whole file is unusable (layout, encoding, size); null when rows were validated.
    file_error VARCHAR(300),
    -- First and last sale date of the valid rows.
    period_start DATE,
    period_end DATE,
    row_count INTEGER NOT NULL DEFAULT 0
        CHECK (row_count >= 0),
    valid_row_count INTEGER NOT NULL DEFAULT 0
        CHECK (valid_row_count >= 0),
    invalid_row_count INTEGER NOT NULL DEFAULT 0
        CHECK (invalid_row_count >= 0),
    duplicate_row_count INTEGER NOT NULL DEFAULT 0
        CHECK (duplicate_row_count >= 0),
    -- Totals of the valid rows, in LKR.
    cash_sales_total NUMERIC(19, 2) NOT NULL DEFAULT 0
        CHECK (cash_sales_total >= 0),
    card_sales_total NUMERIC(19, 2) NOT NULL DEFAULT 0
        CHECK (card_sales_total >= 0),
    -- Users managed by identity-service; stored as IDs only.
    uploaded_by UUID NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    approval_note VARCHAR(500),
    -- Valid rows recorded as daily sales, and invalid rows left out, at approval.
    imported_row_count INTEGER
        CHECK (imported_row_count >= 0),
    excluded_row_count INTEGER
        CHECK (excluded_row_count >= 0),
    rejected_by UUID,
    rejected_at TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    version INTEGER NOT NULL DEFAULT 1
        CHECK (version > 0),
    CONSTRAINT uq_sales_import_batches_company_id_id
        UNIQUE (company_id, id),
    CONSTRAINT ck_sales_import_batches_period
        CHECK (period_end IS NULL OR period_start IS NULL OR period_end >= period_start),
    CONSTRAINT ck_sales_import_batches_row_counts
        CHECK (row_count = valid_row_count + invalid_row_count + duplicate_row_count),
    CONSTRAINT ck_sales_import_batches_approved
        CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND imported_row_count IS NOT NULL AND excluded_row_count IS NOT NULL)),
    CONSTRAINT ck_sales_import_batches_rejected
        CHECK ((status = 'REJECTED') = (rejected_by IS NOT NULL AND rejected_at IS NOT NULL
            AND rejection_reason IS NOT NULL)),
    CONSTRAINT ck_sales_import_batches_invalid_reason
        CHECK (file_error IS NULL OR status = 'INVALID')
);

CREATE INDEX idx_sales_import_batches_company_uploaded_at
    ON sales_import_batches (company_id, uploaded_at DESC);

CREATE INDEX idx_sales_import_batches_company_status
    ON sales_import_batches (company_id, status);

-- The same file cannot be waiting for approval or approved twice for one company; concurrent
-- uploads of it are serialised by this index. A rejected or invalid file may be uploaded again.
CREATE UNIQUE INDEX uq_sales_import_batches_active_file
    ON sales_import_batches (company_id, file_sha256)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED');

-- One data row of an uploaded file, kept for review after approval or rejection.
CREATE TABLE sales_import_rows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL,
    -- Line number in the file (the header is line 1).
    row_number INTEGER NOT NULL
        CHECK (row_number > 1),
    sale_date DATE,
    -- Null when the cell was blank or unreadable; zero is a real value.
    cash_sales_amount NUMERIC(19, 2)
        CHECK (cash_sales_amount >= 0),
    card_sales_amount NUMERIC(19, 2)
        CHECK (card_sales_amount >= 0),
    -- DUPLICATE: the date appears more than once in the file (duplicate_date) or already has
    -- recorded sales; it blocks approval.
    validation_status VARCHAR(20) NOT NULL
        CHECK (validation_status IN ('VALID', 'INVALID', 'DUPLICATE')),
    duplicate_date BOOLEAN NOT NULL DEFAULT false,
    -- [{"field", "code", "message"}]; messages name the problem, not the value.
    validation_errors JSONB NOT NULL DEFAULT '[]',
    -- The cells as written, only for rows that are not valid, so they can be corrected.
    raw_row JSONB,
    CONSTRAINT fk_sales_import_rows_batch
        FOREIGN KEY (batch_id) REFERENCES sales_import_batches (id) ON DELETE RESTRICT,
    CONSTRAINT uq_sales_import_rows_batch_row
        UNIQUE (batch_id, row_number),
    CONSTRAINT ck_sales_import_rows_valid_values
        CHECK (validation_status <> 'VALID'
            OR (sale_date IS NOT NULL AND cash_sales_amount IS NOT NULL AND card_sales_amount IS NOT NULL))
);

CREATE INDEX idx_sales_import_rows_batch_status
    ON sales_import_rows (batch_id, validation_status);

CREATE INDEX idx_sales_import_rows_batch_sale_date
    ON sales_import_rows (batch_id, sale_date);

-- Imported daily sales point to their approved batch, of the same company.
ALTER TABLE daily_sales
    ADD COLUMN import_batch_id UUID;

ALTER TABLE daily_sales
    DROP CONSTRAINT daily_sales_entry_source_check;

ALTER TABLE daily_sales
    ADD CONSTRAINT daily_sales_entry_source_check
        CHECK (entry_source IN ('UI', 'HISTORICAL_IMPORT'));

ALTER TABLE daily_sales
    ADD CONSTRAINT ck_daily_sales_import_batch_matches_source
        CHECK ((entry_source = 'HISTORICAL_IMPORT') = (import_batch_id IS NOT NULL));

ALTER TABLE daily_sales
    ADD CONSTRAINT fk_daily_sales_import_batch
        FOREIGN KEY (company_id, import_batch_id) REFERENCES sales_import_batches (company_id, id)
            ON DELETE RESTRICT;

CREATE INDEX idx_daily_sales_import_batch
    ON daily_sales (import_batch_id)
    WHERE import_batch_id IS NOT NULL;

-- Schema document section 4.4: written in the same transaction as the change it records.
CREATE TABLE cash_management_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    actor_type VARCHAR(20) NOT NULL
        CHECK (actor_type IN ('USER', 'SYSTEM')),
    actor_user_id UUID,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason TEXT,
    before_data JSONB,
    after_data JSONB,
    correlation_id UUID,
    CONSTRAINT ck_cash_management_audit_events_user_actor
        CHECK (actor_type <> 'USER' OR actor_user_id IS NOT NULL)
);

CREATE INDEX idx_cash_management_audit_events_company_occurred_at
    ON cash_management_audit_events (company_id, occurred_at DESC);

CREATE INDEX idx_cash_management_audit_events_entity
    ON cash_management_audit_events (entity_type, entity_id, occurred_at DESC);

CREATE INDEX idx_cash_management_audit_events_correlation
    ON cash_management_audit_events (correlation_id);

-- Append-only: application code cannot change or remove audit history. A database superuser can
-- still bypass these triggers.
CREATE FUNCTION reject_cash_management_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'cash_management_audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cash_management_audit_events_no_update_or_delete
    BEFORE UPDATE OR DELETE ON cash_management_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_cash_management_audit_change();

CREATE TRIGGER cash_management_audit_events_no_truncate
    BEFORE TRUNCATE ON cash_management_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_cash_management_audit_change();
