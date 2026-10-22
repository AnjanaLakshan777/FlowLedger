-- An approved historical import can be reversed: its daily sales are deleted and the batch stays
-- as REVERSED history, still showing who approved it. Its dates and its file are then free for a
-- corrected import (the active-file index covers PENDING_APPROVAL and APPROVED only). Additive:
-- existing batches keep their status.

ALTER TABLE sales_import_batches
    DROP CONSTRAINT sales_import_batches_status_check;

ALTER TABLE sales_import_batches
    ADD CONSTRAINT sales_import_batches_status_check
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'INVALID', 'REVERSED'));

-- A reversed import was approved first, so it keeps its approval.
ALTER TABLE sales_import_batches
    DROP CONSTRAINT ck_sales_import_batches_approved;

ALTER TABLE sales_import_batches
    ADD CONSTRAINT ck_sales_import_batches_approved
        CHECK ((status IN ('APPROVED', 'REVERSED')) = (approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND imported_row_count IS NOT NULL AND excluded_row_count IS NOT NULL));

ALTER TABLE sales_import_batches
    ADD COLUMN reversed_by UUID,
    ADD COLUMN reversed_at TIMESTAMPTZ,
    ADD COLUMN reversal_reason TEXT;

ALTER TABLE sales_import_batches
    ADD CONSTRAINT ck_sales_import_batches_reversed
        CHECK ((status = 'REVERSED') = (reversed_by IS NOT NULL AND reversed_at IS NOT NULL
            AND reversal_reason IS NOT NULL));
