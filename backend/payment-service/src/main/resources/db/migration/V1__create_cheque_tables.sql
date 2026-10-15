CREATE TABLE cheques (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Company managed by identity-service; stored as an ID only, no cross-service FK.
    company_id UUID NOT NULL,
    -- Bank account managed by bank-integration-service; stored as an ID only.
    bank_account_id UUID NOT NULL,
    -- Kept as text so leading zeros are preserved.
    cheque_number VARCHAR(40) NOT NULL
        CHECK (cheque_number ~ '^[0-9]+$'),
    payee_name VARCHAR(200) NOT NULL,
    -- Not in schema v1; added because the finalized UI records a payment category.
    category VARCHAR(32) NOT NULL
        CHECK (category IN ('SUPPLIER_PAYMENT', 'SALARY', 'TAX', 'OTHER_EXPENSES')),
    amount NUMERIC(19, 2) NOT NULL
        CHECK (amount > 0),
    issue_date DATE NOT NULL,
    expected_clearing_date DATE NOT NULL,
    -- Only ISSUED and CANCELLED. A cheque is never marked paid automatically.
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('ISSUED', 'CANCELLED')),
    notes TEXT,
    -- Users managed by identity-service; stored as IDs only.
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID,
    updated_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
        CHECK (version > 0),
    CONSTRAINT uq_cheques_company_account_number
        UNIQUE (company_id, bank_account_id, cheque_number),
    CONSTRAINT uq_cheques_company_id_id
        UNIQUE (company_id, id),
    CONSTRAINT ck_cheques_clearing_not_before_issue
        CHECK (expected_clearing_date >= issue_date),
    CONSTRAINT ck_cheques_update_actor_time_pair
        CHECK ((updated_by IS NULL) = (updated_at IS NULL))
);

CREATE INDEX idx_cheques_company_status_clearing
    ON cheques (company_id, status, expected_clearing_date);

CREATE INDEX idx_cheques_company_issue_date
    ON cheques (company_id, issue_date DESC);

CREATE TABLE payment_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    actor_user_id UUID,
    action VARCHAR(80) NOT NULL,
    cheque_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Required by the application for cancellations.
    reason TEXT,
    before_data JSONB,
    after_data JSONB,
    CONSTRAINT fk_payment_audit_events_cheque
        FOREIGN KEY (company_id, cheque_id) REFERENCES cheques (company_id, id)
);

CREATE INDEX idx_payment_audit_events_company_occurred_at
    ON payment_audit_events (company_id, occurred_at DESC);

CREATE INDEX idx_payment_audit_events_cheque_occurred_at
    ON payment_audit_events (cheque_id, occurred_at DESC);

-- Audit history is append-only. These triggers reject changes from application code; a database
-- superuser can still bypass them, so production also needs a runtime role without that power.
CREATE FUNCTION reject_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment_audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_audit_events_no_update_or_delete
    BEFORE UPDATE OR DELETE ON payment_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

CREATE TRIGGER payment_audit_events_no_truncate
    BEFORE TRUNCATE ON payment_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_change();

-- Transactional outbox: cheque events are written in the same transaction as the cheque change
-- and published later by an outbox worker (not part of this service version).
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
