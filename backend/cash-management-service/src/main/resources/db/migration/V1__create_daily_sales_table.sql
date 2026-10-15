CREATE TABLE daily_sales (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Company managed by identity-service; stored as an ID only, no cross-service FK.
    company_id UUID NOT NULL,
    -- The business date of the sales, not the date the money reached the bank.
    sale_date DATE NOT NULL,
    cash_sales_amount NUMERIC(19, 2) NOT NULL DEFAULT 0
        CHECK (cash_sales_amount >= 0),
    card_sales_amount NUMERIC(19, 2) NOT NULL DEFAULT 0
        CHECK (card_sales_amount >= 0),
    -- Only manual entry exists so far. HISTORICAL_IMPORT and import_batch_id are added together
    -- with sales_import_batches, so imported rows cannot exist without an approved batch.
    entry_source VARCHAR(20) NOT NULL
        CHECK (entry_source IN ('UI')),
    -- User managed by identity-service; stored as an ID only.
    recorded_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID,
    updated_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
        CHECK (version > 0),
    -- One record per company per sales date. Its index also serves (company_id, sale_date DESC) lookups.
    CONSTRAINT uq_daily_sales_company_sale_date
        UNIQUE (company_id, sale_date),
    CONSTRAINT uq_daily_sales_company_id_id
        UNIQUE (company_id, id),
    -- A saved record must contain some sales; a day without sales simply has no record.
    CONSTRAINT ck_daily_sales_total_positive
        CHECK (cash_sales_amount + card_sales_amount > 0),
    CONSTRAINT ck_daily_sales_update_actor_time_pair
        CHECK ((updated_by IS NULL) = (updated_at IS NULL))
);
