package com.flowledger.forecast.calculation;

import java.math.BigDecimal;

/**
 * Settings of a forecast model configuration, stored as its JSON parameters. The defaults are
 * provisional (see docs/decisions.md). The last four settings exist only for PREVIOUS_YEAR_PATTERN
 * and are null in older WEEKDAY_AVERAGE configurations.
 *
 * @param strategy                 code of the inflow strategy to use
 * @param lookbackWeeks            WEEKDAY_AVERAGE: weeks of bank history averaged; PREVIOUS_YEAR_PATTERN:
 *                                 weeks of the growth window before the balance date
 * @param minimumSamplesPerWeekday days needed for every weekday (statement-covered days, or comparable
 *                                 days in each channel) to estimate inflows
 * @param balanceStaleAfterDays    a balance older than this many days is labelled stale
 * @param cashReceiptLagDays       days from an imported cash sale to its bank deposit
 * @param cardReceiptLagDays       days from an imported card sale to its settlement
 * @param growthRatioWarningLow    a growth ratio below this is reported (not capped)
 * @param growthRatioWarningHigh   a growth ratio above this is reported (not capped)
 */
public record ForecastParameters(
        String strategy,
        int lookbackWeeks,
        int minimumSamplesPerWeekday,
        int balanceStaleAfterDays,
        Integer cashReceiptLagDays,
        Integer cardReceiptLagDays,
        BigDecimal growthRatioWarningLow,
        BigDecimal growthRatioWarningHigh
) {

    /** The settings of version-1 configurations, kept for them and for saved runs. */
    public static final ForecastParameters DEFAULTS =
            new ForecastParameters(WeekdayAverageStrategy.CODE, 8, 4, 3, null, null, null, null);

    /** The settings of new configurations (Option B). Must match the V6 migration. */
    public static final ForecastParameters PREVIOUS_YEAR_DEFAULTS =
            new ForecastParameters(PreviousYearPatternStrategy.CODE, 8, 4, 3, 1, 1, new BigDecimal("0.5"),
                    new BigDecimal("2.0"));
}
