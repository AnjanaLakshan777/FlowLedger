-- payment-service records cheques the bank returned unpaid as BOUNCED. The projection keeps them,
-- like CANCELLED ones, and the forecast still selects ISSUED cheques only. Additive: existing rows
-- are unchanged.

ALTER TABLE forecast_cheques
    DROP CONSTRAINT forecast_cheques_status_check;

ALTER TABLE forecast_cheques
    ADD CONSTRAINT forecast_cheques_status_check
        CHECK (status IN ('ISSUED', 'CANCELLED', 'BOUNCED'));
