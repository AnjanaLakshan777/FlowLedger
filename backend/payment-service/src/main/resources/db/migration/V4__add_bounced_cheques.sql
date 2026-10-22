-- A cheque the bank returned unpaid is recorded as BOUNCED, with the date it bounced. Like a
-- cancelled cheque it stays on record and is no longer a forecast outflow. Additive: existing
-- cheques keep their status and have no bounce date.

ALTER TABLE cheques
    DROP CONSTRAINT cheques_status_check;

ALTER TABLE cheques
    ADD CONSTRAINT cheques_status_check
        CHECK (status IN ('ISSUED', 'CANCELLED', 'BOUNCED'));

ALTER TABLE cheques
    ADD COLUMN bounced_on DATE;

ALTER TABLE cheques
    ADD CONSTRAINT ck_cheques_bounced_on
        CHECK ((status = 'BOUNCED') = (bounced_on IS NOT NULL) AND (bounced_on IS NULL OR bounced_on >= issue_date));
