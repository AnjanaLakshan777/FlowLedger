CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    registration_number VARCHAR(80),
    default_currency CHAR(3) NOT NULL DEFAULT 'LKR',
    timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Colombo',
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_companies_status_name
    ON companies (status, name);