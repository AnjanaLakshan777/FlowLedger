-- Option B (docs/decisions.md, section 10): every company whose active forecast configuration uses
-- WEEKDAY_AVERAGE gets a new active version using PREVIOUS_YEAR_PATTERN. The old version is only
-- deactivated, never changed or deleted: saved runs keep referring to it and keep their config
-- snapshot, model version, input digest and results. Companies without a configuration get the
-- new defaults on their first run (ForecastModelConfig.defaults). The parameters must match
-- ForecastParameters.PREVIOUS_YEAR_DEFAULTS.

CREATE TEMPORARY TABLE previous_year_pattern_switch ON COMMIT DROP AS
SELECT company_id, max(version) + 1 AS next_version
FROM forecast_model_configs
WHERE company_id IN (
    SELECT company_id FROM forecast_model_configs
    WHERE is_active AND parameters ->> 'strategy' = 'WEEKDAY_AVERAGE'
)
GROUP BY company_id;

UPDATE forecast_model_configs
SET is_active = false
WHERE is_active
  AND company_id IN (SELECT company_id FROM previous_year_pattern_switch);

-- created_by: the nil UUID marks a configuration created by this migration, not by a user.
INSERT INTO forecast_model_configs
    (company_id, version, is_active, horizon_days, minimum_balance, method_code, parameters, created_by)
SELECT company_id, next_version, true, 84, 0.00, 'PREVIOUS_YEAR_CORRESPONDING_WEEK_PATTERN',
       '{"strategy": "PREVIOUS_YEAR_PATTERN", "lookbackWeeks": 8, "minimumSamplesPerWeekday": 4,
         "balanceStaleAfterDays": 3, "cashReceiptLagDays": 1, "cardReceiptLagDays": 1,
         "growthRatioWarningLow": 0.5, "growthRatioWarningHigh": 2.0}'::jsonb,
       '00000000-0000-0000-0000-000000000000'::uuid
FROM previous_year_pattern_switch;
