-- Forecast tables from schema document section 7. The projection tables and processed_events
-- (7.1-7.3, 7.8) are not created yet: forecast inputs are read from the owning services' APIs
-- until an event consumer exists. calendar_holidays (7.9) waits for a verified holiday source.

CREATE TABLE forecast_model_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Company managed by identity-service; stored as an ID only, no cross-service FK.
    company_id UUID NOT NULL,
    version INTEGER NOT NULL
        CHECK (version > 0),
    is_active BOOLEAN NOT NULL DEFAULT false,
    horizon_days SMALLINT NOT NULL
        CHECK (horizon_days = 84),
    -- Confirmed rule: a cash shortage is a projected closing balance below LKR 0.
    minimum_balance NUMERIC(19, 2) NOT NULL DEFAULT 0.00
        CHECK (minimum_balance = 0),
    method_code VARCHAR(80) NOT NULL,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_forecast_model_configs_company_id_id
        UNIQUE (company_id, id),
    CONSTRAINT uq_forecast_model_configs_company_version
        UNIQUE (company_id, version)
);

CREATE UNIQUE INDEX uq_forecast_model_configs_one_active
    ON forecast_model_configs (company_id)
    WHERE is_active;

CREATE INDEX idx_forecast_model_configs_company_created_at
    ON forecast_model_configs (company_id, created_at DESC);

CREATE TABLE forecast_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    config_id UUID NOT NULL,
    -- Added: the bank-integration-service account whose balance and history were used.
    bank_account_id UUID NOT NULL,
    run_status VARCHAR(16) NOT NULL
        CHECK (run_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    trigger_type VARCHAR(16) NOT NULL
        CHECK (trigger_type IN ('MANUAL', 'SCHEDULED', 'DATA_CHANGE')),
    as_of_at TIMESTAMPTZ NOT NULL,
    horizon_start_date DATE NOT NULL,
    horizon_end_date DATE NOT NULL,
    opening_balance_amount NUMERIC(19, 2) NOT NULL,
    opening_balance_as_of TIMESTAMPTZ NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'LKR'
        CHECK (currency ~ '^[A-Z]{3}$'),
    -- Added: data-quality labels shown with the forecast.
    balance_stale BOOLEAN NOT NULL,
    inflow_estimate_status VARCHAR(24) NOT NULL
        CHECK (inflow_estimate_status IN ('ESTIMATED', 'INSUFFICIENT_HISTORY')),
    data_quality JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_version VARCHAR(40) NOT NULL,
    config_snapshot JSONB NOT NULL,
    input_snapshot_hash CHAR(64)
        CHECK (input_snapshot_hash ~ '^[0-9a-f]{64}$'),
    triggered_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    error_summary TEXT,
    CONSTRAINT uq_forecast_runs_company_id_id
        UNIQUE (company_id, id),
    CONSTRAINT fk_forecast_runs_config
        FOREIGN KEY (company_id, config_id) REFERENCES forecast_model_configs (company_id, id),
    -- Confirmed rule: the horizon is exactly 84 days.
    CONSTRAINT ck_forecast_runs_horizon
        CHECK (horizon_end_date = horizon_start_date + 83),
    CONSTRAINT ck_forecast_runs_completed
        CHECK (run_status <> 'SUCCEEDED' OR completed_at IS NOT NULL)
);

-- The same inputs on the same day produce one run, also when requested twice at once.
CREATE UNIQUE INDEX uq_forecast_runs_company_input_hash
    ON forecast_runs (company_id, input_snapshot_hash)
    WHERE input_snapshot_hash IS NOT NULL;

CREATE INDEX idx_forecast_runs_company_created_at
    ON forecast_runs (company_id, created_at DESC);

CREATE INDEX idx_forecast_runs_company_horizon
    ON forecast_runs (company_id, horizon_start_date, horizon_end_date);

CREATE INDEX idx_forecast_runs_status_created_at
    ON forecast_runs (run_status, created_at);

CREATE TABLE forecast_daily_results (
    forecast_run_id UUID NOT NULL,
    forecast_date DATE NOT NULL,
    -- NULL, not 0, when history is too short to estimate inflows (schema v1 had NOT NULL).
    expected_cash_sales_deposits_amount NUMERIC(19, 2)
        CHECK (expected_cash_sales_deposits_amount >= 0),
    expected_card_sales_settlements_amount NUMERIC(19, 2)
        CHECK (expected_card_sales_settlements_amount >= 0),
    expected_sales_inflows_amount NUMERIC(19, 2)
        CHECK (expected_sales_inflows_amount >= 0),
    expected_cheque_outflows_amount NUMERIC(19, 2) NOT NULL DEFAULT 0
        CHECK (expected_cheque_outflows_amount >= 0),
    -- Added: number of cheques behind the expected outflow.
    expected_cheque_count INTEGER NOT NULL DEFAULT 0
        CHECK (expected_cheque_count >= 0),
    opening_balance_amount NUMERIC(19, 2) NOT NULL,
    closing_balance_amount NUMERIC(19, 2) NOT NULL,
    is_cash_shortage BOOLEAN NOT NULL,
    CONSTRAINT pk_forecast_daily_results
        PRIMARY KEY (forecast_run_id, forecast_date),
    CONSTRAINT fk_forecast_daily_results_run
        FOREIGN KEY (forecast_run_id) REFERENCES forecast_runs (id) ON DELETE RESTRICT,
    CONSTRAINT ck_forecast_daily_results_inflows_together
        CHECK ((expected_cash_sales_deposits_amount IS NULL) = (expected_sales_inflows_amount IS NULL)
            AND (expected_card_sales_settlements_amount IS NULL) = (expected_sales_inflows_amount IS NULL)),
    CONSTRAINT ck_forecast_daily_results_inflow_sum
        CHECK (expected_sales_inflows_amount
            = expected_cash_sales_deposits_amount + expected_card_sales_settlements_amount),
    CONSTRAINT ck_forecast_daily_results_closing
        CHECK (closing_balance_amount = opening_balance_amount
            + coalesce(expected_sales_inflows_amount, 0) - expected_cheque_outflows_amount),
    CONSTRAINT ck_forecast_daily_results_shortage
        CHECK (is_cash_shortage = (closing_balance_amount < 0))
);

CREATE INDEX idx_forecast_daily_results_date
    ON forecast_daily_results (forecast_date);

CREATE TABLE forecast_run_cheques (
    forecast_run_id UUID NOT NULL,
    -- Cheque managed by payment-service; stored as an ID only.
    cheque_id UUID NOT NULL,
    cheque_number VARCHAR(40) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL
        CHECK (amount > 0),
    expected_clearing_date DATE NOT NULL,
    status_at_run VARCHAR(16) NOT NULL
        CHECK (status_at_run IN ('ISSUED', 'CANCELLED')),
    -- Added: the cheque version the run used.
    source_version INTEGER NOT NULL
        CHECK (source_version > 0),
    CONSTRAINT pk_forecast_run_cheques
        PRIMARY KEY (forecast_run_id, cheque_id),
    CONSTRAINT fk_forecast_run_cheques_run
        FOREIGN KEY (forecast_run_id) REFERENCES forecast_runs (id) ON DELETE RESTRICT
);

CREATE INDEX idx_forecast_run_cheques_cheque
    ON forecast_run_cheques (cheque_id);

-- A saved run is immutable; a later forecast is a new run. These triggers reject changes from
-- application code; a database superuser can still bypass them.
CREATE FUNCTION reject_forecast_run_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER forecast_runs_no_update_or_delete
    BEFORE UPDATE OR DELETE ON forecast_runs
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_run_change();

CREATE TRIGGER forecast_runs_no_truncate
    BEFORE TRUNCATE ON forecast_runs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_forecast_run_change();

CREATE TRIGGER forecast_daily_results_no_update_or_delete
    BEFORE UPDATE OR DELETE ON forecast_daily_results
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_run_change();

CREATE TRIGGER forecast_daily_results_no_truncate
    BEFORE TRUNCATE ON forecast_daily_results
    FOR EACH STATEMENT EXECUTE FUNCTION reject_forecast_run_change();

CREATE TRIGGER forecast_run_cheques_no_update_or_delete
    BEFORE UPDATE OR DELETE ON forecast_run_cheques
    FOR EACH ROW EXECUTE FUNCTION reject_forecast_run_change();

CREATE TRIGGER forecast_run_cheques_no_truncate
    BEFORE TRUNCATE ON forecast_run_cheques
    FOR EACH STATEMENT EXECUTE FUNCTION reject_forecast_run_change();
