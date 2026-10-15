package com.flowledger.forecast.calculation;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which days are covered by imported bank statements. Only for these days is a missing
 * transaction a real "nothing happened"; other days are unknown.
 */
public final class StatementCoverage {

    public record Period(LocalDate start, LocalDate end) {
    }

    private StatementCoverage() {
    }

    public static Set<LocalDate> coveredDates(Collection<Period> periods, LocalDate from, LocalDate to) {
        Set<LocalDate> covered = new TreeSet<>();
        for (Period period : periods) {
            LocalDate start = period.start().isBefore(from) ? from : period.start();
            LocalDate end = period.end().isAfter(to) ? to : period.end();
            if (!start.isAfter(end)) {
                start.datesUntil(end.plusDays(1)).forEach(covered::add);
            }
        }
        return covered;
    }
}
