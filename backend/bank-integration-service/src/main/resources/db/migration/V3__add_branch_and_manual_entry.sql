-- The account's branch, and manual bank entry for accounts whose connection is MANUAL. Additive
-- only: existing accounts have no branch, and existing transactions all came from statements.

ALTER TABLE bank_accounts
    ADD COLUMN branch_name VARCHAR(120);

-- How a transaction reached FlowLedger. Manual transactions have no import batch and no running
-- balance; entered_by is the user who typed them in.
ALTER TABLE bank_transactions
    ADD COLUMN entry_source VARCHAR(24) NOT NULL DEFAULT 'STATEMENT_IMPORT'
        CHECK (entry_source IN ('STATEMENT_IMPORT', 'MANUAL')),
    ADD COLUMN entered_by UUID;

ALTER TABLE bank_transactions
    ADD CONSTRAINT ck_bank_transactions_manual_entry
        CHECK ((entry_source = 'MANUAL') = (entered_by IS NOT NULL)
            AND (entry_source <> 'MANUAL' OR source_import_batch_id IS NULL));
