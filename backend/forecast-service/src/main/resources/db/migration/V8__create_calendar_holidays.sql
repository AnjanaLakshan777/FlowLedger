-- Sri Lankan holidays (schema document section 7.9), shown on the cash-flow calendar. They are
-- reference data for every company: a platform SYSTEM_ADMIN replaces a year's list from the Central
-- Bank of Sri Lanka's "Bank Holidays" page. They do not change forecasts yet (the holiday rule is open).
-- Simplified from the schema document: one row per named holiday with its B/P/M flags instead of a
-- separate holiday_type column, and no Sinhala or Tamil names yet.

CREATE TABLE calendar_holidays (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    holiday_date DATE NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    is_bank_holiday BOOLEAN NOT NULL DEFAULT false,
    is_public_holiday BOOLEAN NOT NULL DEFAULT false,
    is_mercantile_holiday BOOLEAN NOT NULL DEFAULT false,
    is_poya_day BOOLEAN NOT NULL DEFAULT false,
    source_name VARCHAR(200) NOT NULL,
    source_url TEXT,
    source_document_year SMALLINT NOT NULL,
    -- The platform SYSTEM_ADMIN who imported the list; the nil UUID for rows seeded by a migration.
    imported_by UUID NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_calendar_holidays_year
        CHECK (source_document_year = EXTRACT(YEAR FROM holiday_date)),
    CONSTRAINT ck_calendar_holidays_kind
        CHECK (is_bank_holiday OR is_public_holiday OR is_mercantile_holiday),
    CONSTRAINT uq_calendar_holidays_date_name
        UNIQUE (holiday_date, name_en)
);

CREATE INDEX idx_calendar_holidays_date ON calendar_holidays (holiday_date);

-- Central Bank of Sri Lanka, Bank Holidays for 2026 and 2027 (B = bank, P = public, M = mercantile).
-- All Saturdays and Sundays are also bank holidays; they are not stored.
INSERT INTO calendar_holidays (holiday_date, name_en, is_bank_holiday, is_public_holiday, is_mercantile_holiday,
                               is_poya_day, source_name, source_url, source_document_year, imported_by)
SELECT v.d, v.n, v.b, v.p, v.m, v.n ILIKE '%Full Moon Poya Day%' AND v.n NOT ILIKE 'Day following%',
       'Central Bank of Sri Lanka',
       'https://www.cbsl.gov.lk/en/about/about-the-bank/bank-holidays-' || EXTRACT(YEAR FROM v.d)::int,
       EXTRACT(YEAR FROM v.d)::smallint, '00000000-0000-0000-0000-000000000000'::uuid
FROM (VALUES
    (DATE '2026-01-03', 'Duruthu Full Moon Poya Day', true, true, false),
    (DATE '2026-01-15', 'Tamil Thai Pongal Day', true, true, true),
    (DATE '2026-02-01', 'Navam Full Moon Poya Day', true, true, false),
    (DATE '2026-02-04', 'Independence Day', true, true, true),
    (DATE '2026-02-15', 'Mahasivarathri Day', true, true, false),
    (DATE '2026-03-02', 'Medin Full Moon Poya Day', true, true, false),
    (DATE '2026-03-21', 'Id-Ul-Fitre (Ramazan Festival Day)', true, true, false),
    (DATE '2026-04-01', 'Bak Full Moon Poya Day', true, true, false),
    (DATE '2026-04-03', 'Good Friday', true, true, false),
    (DATE '2026-04-13', 'Day prior to Sinhala & Tamil New Year Day', true, true, true),
    (DATE '2026-04-14', 'Sinhala & Tamil New Year Day', true, true, true),
    (DATE '2026-05-01', 'Vesak Full Moon Poya Day', true, true, false),
    (DATE '2026-05-01', 'May Day (International Workers'' Day)', true, true, true),
    (DATE '2026-05-02', 'Day following Vesak Full Moon Poya Day', true, true, true),
    (DATE '2026-05-28', 'Id-Ul-Allah (Hadji Festival Day)', true, true, false),
    (DATE '2026-05-30', 'Adhi Poson Full Moon Poya Day', true, true, false),
    (DATE '2026-06-29', 'Poson Full Moon Poya Day', true, true, false),
    (DATE '2026-07-29', 'Esala Full Moon Poya Day', true, true, false),
    (DATE '2026-08-26', 'Milad-Un-Nabi (Holy Prophet''s Birthday)', true, true, true),
    (DATE '2026-08-27', 'Nikini Full Moon Poya Day', true, true, false),
    (DATE '2026-09-26', 'Binara Full Moon Poya Day', true, true, false),
    (DATE '2026-10-25', 'Vap Full Moon Poya Day', true, true, false),
    (DATE '2026-11-08', 'Deepawali Festival Day', true, true, false),
    (DATE '2026-11-24', 'Ill Full Moon Poya Day', true, true, false),
    (DATE '2026-12-23', 'Unduwap Full Moon Poya Day', true, true, false),
    (DATE '2026-12-25', 'Christmas Day', true, true, true),
    (DATE '2027-01-15', 'Tamil Thai Pongal Day', true, true, true),
    (DATE '2027-01-22', 'Duruthu Full Moon Poya Day', true, true, false),
    (DATE '2027-02-04', 'Independence Day', true, true, true),
    (DATE '2027-02-20', 'Navam Full Moon Poya Day', true, true, false),
    (DATE '2027-03-06', 'Mahasivarathri Day', true, true, false),
    (DATE '2027-03-10', 'Eid-Ul-Fitr (Ramazan Festival Day)', true, true, false),
    (DATE '2027-03-22', 'Medin Full Moon Poya Day', true, true, false),
    (DATE '2027-03-26', 'Good Friday', true, true, false),
    (DATE '2027-04-13', 'Day prior to Sinhala & Tamil New Year Day', true, true, true),
    (DATE '2027-04-14', 'Sinhala & Tamil New Year Day', true, true, true),
    (DATE '2027-04-20', 'Bak Full Moon Poya Day', true, true, false),
    (DATE '2027-05-01', 'May Day (International Workers'' Day)', true, true, true),
    (DATE '2027-05-17', 'Eid-Ul-Allha (Hadji Festival Day)', true, true, false),
    (DATE '2027-05-19', 'Vesak Full Moon Poya Day', true, true, false),
    (DATE '2027-05-20', 'Day following Vesak Full Moon Poya Day', true, true, true),
    (DATE '2027-06-18', 'Poson Full Moon Poya Day', true, true, false),
    (DATE '2027-07-18', 'Esala Full Moon Poya Day', true, true, false),
    (DATE '2027-08-15', 'Milad-Un-Nabi (Holy Prophet''s Birthday)', true, true, true),
    (DATE '2027-08-16', 'Nikini Full Moon Poya Day', true, true, false),
    (DATE '2027-09-15', 'Binara Full Moon Poya Day', true, true, false),
    (DATE '2027-10-15', 'Vap Full Moon Poya Day', true, true, false),
    (DATE '2027-10-28', 'Deepawali Festival Day', true, true, false),
    (DATE '2027-11-13', 'Ill Full Moon Poya Day', true, true, false),
    (DATE '2027-12-13', 'Unduwap Full Moon Poya Day', true, true, false),
    (DATE '2027-12-25', 'Christmas Day', true, true, true)
) AS v(d, n, b, p, m);
