package com.flowledger.forecast.calculation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Result of an inflow strategy: an expected inflow for each forecast date, or none at all when
 * the history is insufficient.
 *
 * @param samplesPerWeekday statement-covered days per weekday that the estimate is based on
 */
public record InflowEstimate(
        InflowEstimateStatus status,
        Map<LocalDate, ExpectedInflow> byDate,
        Map<DayOfWeek, Integer> samplesPerWeekday
) {

    public static InflowEstimate insufficient(Map<DayOfWeek, Integer> samplesPerWeekday) {
        return new InflowEstimate(InflowEstimateStatus.INSUFFICIENT_HISTORY, Map.of(), samplesPerWeekday);
    }

    public Optional<ExpectedInflow> on(LocalDate date) {
        return Optional.ofNullable(byDate.get(date));
    }
}
