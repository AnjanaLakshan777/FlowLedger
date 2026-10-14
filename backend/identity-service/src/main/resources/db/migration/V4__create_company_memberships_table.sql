CREATE TABLE company_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL
        REFERENCES companies(id),
    user_id UUID NOT NULL
        REFERENCES users(id),
    company_role VARCHAR(30) NOT NULL
    CHECK (
        company_role IN (
            'COMPANY_ADMIN',
            'FINANCE_CONTROLLER',
            'ACCOUNTANT',
            'CASHIER'
            )
        ),

    status VARCHAR(20) NOT NULL DEFAULT 'INVITED'
    CHECK (
        status IN (
            'ACTIVE',
            'INVITED',
            'SUSPENDED'
        )
    ),
    invited_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_memberships_company_user
        UNIQUE (company_id, user_id)
);

CREATE INDEX idx_company_memberships_user_status
    ON company_memberships (user_id, status);