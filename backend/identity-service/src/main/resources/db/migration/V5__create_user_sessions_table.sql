CREATE TABLE user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL
        REFERENCES users(id),
    refresh_token_hash VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    device_label VARCHAR(160),
    ip_address INET,
    CONSTRAINT chk_user_sessions_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_user_sessions_user_expiry
    ON user_sessions (user_id, expires_at);

CREATE INDEX idx_user_sessions_expiry
    ON user_sessions (expires_at);