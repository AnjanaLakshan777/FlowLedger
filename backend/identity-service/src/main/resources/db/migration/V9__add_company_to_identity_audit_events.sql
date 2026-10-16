-- Users & Settings shows a company's access changes, so each audit event records the company it
-- belongs to. Null for events outside any company. Additive only; existing rows are unchanged.
ALTER TABLE identity_audit_events
    ADD COLUMN company_id UUID REFERENCES companies (id);

CREATE INDEX idx_identity_audit_events_company_occurred_at
    ON identity_audit_events (company_id, occurred_at DESC);
