package com.flowledger.forecast.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * What the forecast was based on and how complete it was, stored with each run so the labels
 * shown with a saved forecast never change.
 *
 * @param historyFrom               first day of the bank history used for inflows
 * @param historyTo                 last day of that history (the balance date)
 * @param statementCoveredDays      days in that range covered by an imported statement
 * @param coveredDaysPerWeekday     covered days per weekday
 * @param unclassifiedCreditCount   UNCLASSIFIED credits in the history; they may hide sales inflows
 * @param unclassifiedCreditAmount  their total
 * @param balanceAgeDays            days between the balance date and the day of the run
 * @param pattern                   PREVIOUS_YEAR_PATTERN details; absent (not stored) for other methods
 *                                  and for runs saved before it existed
 */
public record DataQuality(
        LocalDate historyFrom,
        LocalDate historyTo,
        int statementCoveredDays,
        Map<DayOfWeek, Integer> coveredDaysPerWeekday,
        int minimumDaysPerWeekday,
        int unclassifiedCreditCount,
        BigDecimal unclassifiedCreditAmount,
        long balanceAgeDays,
        int balanceStaleAfterDays,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        PatternQuality pattern
) {
}
