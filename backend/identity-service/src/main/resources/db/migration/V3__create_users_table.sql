CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    account_status VARCHAR(20) NOT NULL
    CHECK (
        account_status IN (
            'ACTIVE',
            'LOCKED',
            'DISABLED',
            'PENDING'
            )
        ),
    platform_role VARCHAR(30) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_users_email_lower
    ON users (lower(email));

CREATE INDEX idx_users_account_status
    ON users (account_status);