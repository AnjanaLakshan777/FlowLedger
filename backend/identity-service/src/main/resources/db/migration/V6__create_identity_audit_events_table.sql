CREATE TABLE identity_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address INET,
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_identity_audit_events_occurred_at
    ON identity_audit_events (occurred_at DESC);

CREATE INDEX idx_identity_audit_events_actor_occurred_at
    ON identity_audit_events (actor_user_id, occurred_at DESC);

CREATE INDEX idx_identity_audit_events_target
    ON identity_audit_events (target_type, target_id);