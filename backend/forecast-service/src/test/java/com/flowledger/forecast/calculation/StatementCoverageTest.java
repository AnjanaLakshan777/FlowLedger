package com.flowledger.forecast.calculation;

import com.flowledger.forecast.calculation.StatementCoverage.Period;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StatementCoverageTest {

    @Test
    void shouldJoinOverlappingStatementsAndKeepGaps() {
        Set<LocalDate> covered = StatementCoverage.coveredDates(List.of(
                        new Period(date(1), date(10)),
                        new Period(date(8), date(12)),
                        new Period(date(20), date(25))),
                date(1), date(30));

        assertThat(covered).hasSize(18);
        assertThat(covered).contains(date(1), date(12), date(20), date(25));
        assertThat(covered).doesNotContain(date(13), date(19), date(26));
    }

    @Test
    void shouldClipStatementsToTheRequestedRange() {
        Set<LocalDate> covered = StatementCoverage.coveredDates(List.of(
                        new Period(date(1), date(30)),
                        new Period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))),
                date(10), date(12));

        assertThat(covered).containsExactly(date(10), date(11), date(12));
    }

    @Test
    void shouldCoverNothingWithoutStatements() {
        assertThat(StatementCoverage.coveredDates(List.of(), date(1), date(30))).isEmpty();
    }

    private static LocalDate date(int day) {
        return LocalDate.of(2026, 9, day);
    }
}
