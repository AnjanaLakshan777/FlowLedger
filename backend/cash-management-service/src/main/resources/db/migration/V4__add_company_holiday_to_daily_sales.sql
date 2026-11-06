-- A day the company was closed is recorded as a company holiday with no sales, so the screens
-- know the day was not forgotten. Every other record still needs some sales.
ALTER TABLE daily_sales
    ADD COLUMN company_holiday BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE daily_sales
    DROP CONSTRAINT ck_daily_sales_total_positive;

ALTER TABLE daily_sales
    ADD CONSTRAINT ck_daily_sales_total_positive
        CHECK (company_holiday OR cash_sales_amount + card_sales_amount > 0);

-- A company holiday is a closed day: it has no sales, and only manual entry can record one.
ALTER TABLE daily_sales
    ADD CONSTRAINT ck_daily_sales_holiday_without_sales
        CHECK (NOT company_holiday
               OR (cash_sales_amount = 0 AND card_sales_amount = 0 AND entry_source = 'UI'));
