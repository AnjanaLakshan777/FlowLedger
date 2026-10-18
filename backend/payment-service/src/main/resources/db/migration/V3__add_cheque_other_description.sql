-- An "Other Expenses" cheque says what the payment was, so it reads as "Other Expenses - Audit fee".
-- Additive: existing rows are unchanged, and existing OTHER_EXPENSES cheques simply have no description.

ALTER TABLE cheques
    ADD COLUMN other_description VARCHAR(120);

ALTER TABLE cheques
    ADD CONSTRAINT ck_cheques_other_description
        CHECK (other_description IS NULL OR category = 'OTHER_EXPENSES');
