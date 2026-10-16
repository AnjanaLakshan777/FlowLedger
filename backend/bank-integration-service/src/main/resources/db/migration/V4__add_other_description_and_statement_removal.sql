-- An "Other" transaction says what it was, and an imported statement can be removed. Additive:
-- existing rows are unchanged, and existing OTHER transactions simply have no description.

ALTER TABLE bank_transactions
    ADD COLUMN other_description VARCHAR(120);

ALTER TABLE bank_transactions
    ADD CONSTRAINT ck_bank_transactions_other_description
        CHECK (other_description IS NULL OR category = 'OTHER');

-- A removed batch stays as history; its transactions and balance are deleted.
ALTER TABLE bank_statement_import_batches
    DROP CONSTRAINT bank_statement_import_batches_status_check;

ALTER TABLE bank_statement_import_batches
    ADD CONSTRAINT bank_statement_import_batches_status_check
        CHECK (status IN ('UPLOADED', 'VALIDATING', 'IMPORTED', 'FAILED', 'REMOVED'));

ALTER TABLE bank_statement_import_batches
    ADD COLUMN removed_at TIMESTAMPTZ,
    ADD COLUMN removed_by UUID;

ALTER TABLE bank_statement_import_batches
    ADD CONSTRAINT ck_bank_statement_import_batches_removed
        CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL AND removed_by IS NOT NULL));
