-- Forecast's own copy of payment-service cheques, kept current from cheque events (schema document
-- section 7.1), and the events already applied (section 7.8). Saved forecast runs and their cheque
-- snapshots are never changed by these tables. Forecast calculations do not read the projection yet.

CREATE TABLE forecast_cheques (
    -- Cheque, company and bank account are payment-service / identity / bank-integration IDs; no cross-service FKs.
    cheque_id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    bank_account_id UUID NOT NULL,
    cheque_number VARCHAR(40) NOT NULL
        CHECK (cheque_number ~ '^[0-9]+$'),
    category VARCHAR(32) NOT NULL
        CHECK (category IN ('SUPPLIER_PAYMENT', 'SALARY', 'TAX', 'OTHER_EXPENSES')),
    amount NUMERIC(19, 2) NOT NULL
        CHECK (amount > 0),
    issue_date DATE NOT NULL,
    expected_clearing_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('ISSUED', 'CANCELLED')),
    -- The cheque version in payment-service; an event with a version not above this one is stale.
    source_version INTEGER NOT NULL
        CHECK (source_version > 0),
    -- When payment-service wrote the outbox event of the applied version (the start of its
    -- transaction, from the AMQP timestamp). Not the cheque's business update time, which the
    -- events do not carry; schema v1's source_updated_at is therefore not used.
    source_event_created_at TIMESTAMPTZ,
    last_event_id UUID NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_forecast_cheques_clearing_not_before_issue
        CHECK (expected_clearing_date >= issue_date)
);

CREATE INDEX idx_forecast_cheques_company_status_clearing
    ON forecast_cheques (company_id, status, expected_clearing_date);

-- One row per applied event ID, written in the same transaction as the projection change, so a
-- redelivered event is recognised and never applied twice.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    source_service VARCHAR(80) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Outcome and IDs only, never payload values.
    result_summary JSONB
);

CREATE INDEX idx_processed_events_source_processed_at
    ON processed_events (source_service, processed_at DESC);
