CREATE TABLE bank_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Company managed by identity-service; stored as an ID only, no cross-service FK.
    company_id UUID NOT NULL,
    bank_name VARCHAR(160) NOT NULL,
    account_name VARCHAR(160) NOT NULL,
    account_type VARCHAR(24) NOT NULL
        CHECK (account_type IN ('CURRENT')),
    -- Reserved for an encrypted full account number. Not written until encryption and key
    -- management are decided; only the last four digits are stored for now.
    account_number_encrypted BYTEA,
    account_number_last4 CHAR(4)
        CHECK (account_number_last4 ~ '^[0-9]{4}$'),
    currency CHAR(3) NOT NULL DEFAULT 'LKR'
        CHECK (currency ~ '^[A-Z]{3}$'),
    integration_mode VARCHAR(24) NOT NULL
        CHECK (integration_mode IN ('STATEMENT_IMPORT', 'BANK_API', 'SIMULATOR', 'MANUAL')),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_accounts_company_id_id
        UNIQUE (company_id, id)
);

CREATE INDEX idx_bank_accounts_company_status
    ON bank_accounts (company_id, status);

-- MVP rule: a company has at most one active current account.
CREATE UNIQUE INDEX uq_bank_accounts_one_active_current
    ON bank_accounts (company_id)
    WHERE account_type = 'CURRENT' AND status = 'ACTIVE';
