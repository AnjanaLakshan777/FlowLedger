-- Bank statement import, following sections 6.2 to 6.6 of the schema document. Columns marked
-- "added" are not in schema v1; they keep data the statement provides or the import reports.

CREATE TABLE bank_statement_import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    bank_account_id UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL
        CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    -- Added: which statement layout was parsed, so other bank formats can be supported later.
    file_format VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('UPLOADED', 'VALIDATING', 'IMPORTED', 'FAILED')),
    period_start DATE,
    period_end DATE,
    row_count INTEGER NOT NULL DEFAULT 0
        CHECK (row_count >= 0),
    -- Added: what happened to the rows. Duplicates were already imported; skipped rows had a zero amount.
    imported_row_count INTEGER NOT NULL DEFAULT 0
        CHECK (imported_row_count >= 0),
    duplicate_row_count INTEGER NOT NULL DEFAULT 0
        CHECK (duplicate_row_count >= 0),
    skipped_row_count INTEGER NOT NULL DEFAULT 0
        CHECK (skipped_row_count >= 0),
    -- User managed by identity-service; stored as an ID only.
    imported_by UUID NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_summary TEXT,
    -- Added: row-level validation errors of a FAILED batch, as [{line, field, message}].
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT uq_bank_statement_import_batches_company_id_id
        UNIQUE (company_id, id),
    CONSTRAINT fk_bank_statement_import_batches_account
        FOREIGN KEY (company_id, bank_account_id) REFERENCES bank_accounts (company_id, id),
    CONSTRAINT ck_bank_statement_import_batches_period
        CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start),
    CONSTRAINT ck_bank_statement_import_batches_counts
        CHECK (imported_row_count + duplicate_row_count + skipped_row_count <= row_count)
);

CREATE INDEX idx_bank_statement_import_batches_company_imported_at
    ON bank_statement_import_batches (company_id, imported_at DESC);

CREATE INDEX idx_bank_statement_import_batches_account_period
    ON bank_statement_import_batches (bank_account_id, period_start, period_end);

CREATE INDEX idx_bank_statement_import_batches_file_sha256
    ON bank_statement_import_batches (file_sha256);

CREATE TABLE bank_balance_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    bank_account_id UUID NOT NULL,
    -- Can be negative if the bank allows an overdraft. A missing balance is never stored as zero.
    balance_amount NUMERIC(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'LKR'
        CHECK (currency ~ '^[A-Z]{3}$'),
    balance_as_of TIMESTAMPTZ NOT NULL,
    source_type VARCHAR(24) NOT NULL
        CHECK (source_type IN ('STATEMENT_IMPORT', 'BANK_API', 'SIMULATOR', 'MANUAL')),
    source_import_batch_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_bank_balance_snapshots_account
        FOREIGN KEY (company_id, bank_account_id) REFERENCES bank_accounts (company_id, id),
    CONSTRAINT fk_bank_balance_snapshots_import_batch
        FOREIGN KEY (company_id, source_import_batch_id) REFERENCES bank_statement_import_batches (company_id, id),
    -- Added: importing the same statement again must not record the same balance twice.
    CONSTRAINT uq_bank_balance_snapshots_account_as_of_amount
        UNIQUE (bank_account_id, balance_as_of, balance_amount)
);

CREATE INDEX idx_bank_balance_snapshots_company_account_as_of
    ON bank_balance_snapshots (company_id, bank_account_id, balance_as_of DESC);

CREATE TABLE bank_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    bank_account_id UUID NOT NULL,
    external_transaction_id VARCHAR(160),
    deduplication_fingerprint CHAR(64) NOT NULL
        CHECK (deduplication_fingerprint ~ '^[0-9a-f]{64}$'),
    -- The actual bank transaction date, used for Forecast vs Actual.
    transaction_date DATE NOT NULL,
    value_date DATE,
    direction VARCHAR(8) NOT NULL
        CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount NUMERIC(19, 2) NOT NULL
        CHECK (amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'LKR'
        CHECK (currency ~ '^[A-Z]{3}$'),
    description TEXT,
    -- Added: statement details kept for traceability.
    running_balance NUMERIC(19, 2),
    cheque_number VARCHAR(40)
        CHECK (cheque_number ~ '^[0-9]+$'),
    branch_code VARCHAR(20),
    category VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED'
        CHECK (category IN ('CASH_SALES_DEPOSIT', 'CARD_SALES_SETTLEMENT', 'CHEQUE_PAYMENT', 'OTHER', 'UNCLASSIFIED')),
    classification_method VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (classification_method IN ('BANK_METADATA', 'RULE', 'MANUAL', 'UNKNOWN')),
    classified_by UUID,
    classified_at TIMESTAMPTZ,
    source_import_batch_id UUID,
    -- Added: rows are inserted oldest first, so this keeps the bank's order within a day.
    entry_sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_transactions_company_id_id
        UNIQUE (company_id, id),
    CONSTRAINT uq_bank_transactions_account_fingerprint
        UNIQUE (bank_account_id, deduplication_fingerprint),
    CONSTRAINT fk_bank_transactions_account
        FOREIGN KEY (company_id, bank_account_id) REFERENCES bank_accounts (company_id, id),
    CONSTRAINT fk_bank_transactions_import_batch
        FOREIGN KEY (company_id, source_import_batch_id) REFERENCES bank_statement_import_batches (company_id, id),
    -- Added: sales receipts are money in, cheque payments are money out.
    CONSTRAINT ck_bank_transactions_category_direction
        CHECK ((category NOT IN ('CASH_SALES_DEPOSIT', 'CARD_SALES_SETTLEMENT') OR direction = 'CREDIT')
            AND (category <> 'CHEQUE_PAYMENT' OR direction = 'DEBIT')),
    CONSTRAINT ck_bank_transactions_classifier_time_pair
        CHECK ((classified_by IS NULL) = (classified_at IS NULL))
);

CREATE UNIQUE INDEX uq_bank_transactions_account_external_id
    ON bank_transactions (bank_account_id, external_transaction_id)
    WHERE external_transaction_id IS NOT NULL;

CREATE INDEX idx_bank_transactions_company_date
    ON bank_transactions (company_id, transaction_date DESC);

CREATE INDEX idx_bank_transactions_account_date
    ON bank_transactions (bank_account_id, transaction_date DESC);

CREATE INDEX idx_bank_transactions_company_category_date
    ON bank_transactions (company_id, category, transaction_date);

CREATE TABLE bank_integration_audit_events (
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
    after_data JSONB
);

CREATE INDEX idx_bank_integration_audit_events_company_occurred_at
    ON bank_integration_audit_events (company_id, occurred_at DESC);

CREATE INDEX idx_bank_integration_audit_events_entity
    ON bank_integration_audit_events (entity_type, entity_id, occurred_at DESC);

-- Audit history is append-only. These triggers reject changes from application code; a database
-- superuser can still bypass them, so production also needs a runtime role without that power.
CREATE FUNCTION reject_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'bank_integration_audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER bank_integration_audit_events_no_update_or_delete
    BEFORE UPDATE OR DELETE ON bank_integration_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

CREATE TRIGGER bank_integration_audit_events_no_truncate
    BEFORE TRUNCATE ON bank_integration_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_change();

-- Transactional outbox: events are written in the same transaction as the change and published
-- later by an outbox worker (not part of this service version).
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    schema_version SMALLINT NOT NULL DEFAULT 1
        CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0
        CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT
);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_events_aggregate
    ON outbox_events (aggregate_type, aggregate_id, created_at);
