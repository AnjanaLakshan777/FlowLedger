package com.flowledger.forecast.calculation;

/**
 * Settings of a forecast model configuration, stored as its JSON parameters. The defaults are
 * provisional (see docs/decisions.md); the lookback and minimum history are not confirmed rules.
 *
 * @param strategy                 code of the inflow strategy to use
 * @param lookbackWeeks            how many weeks of bank history before the balance date are used
 * @param minimumSamplesPerWeekday statement-covered days needed for every weekday to estimate inflows
 * @param balanceStaleAfterDays    a balance older than this many days is labelled stale
 */
public record ForecastParameters(
        String strategy,
        int lookbackWeeks,
        int minimumSamplesPerWeekday,
        int balanceStaleAfterDays
) {

    public static final ForecastParameters DEFAULTS =
            new ForecastParameters(WeekdayAverageStrategy.CODE, 8, 4, 3);
}
