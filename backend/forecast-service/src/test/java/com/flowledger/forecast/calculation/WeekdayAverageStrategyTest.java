package com.flowledger.forecast.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class WeekdayAverageStrategyTest {

    // Eight weeks ending on Wednesday 14 October 2026.
    private static final LocalDate FROM = LocalDate.of(2026, 8, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 14);
    private static final ForecastParameters PARAMETERS = ForecastParameters.DEFAULTS;
    private static final List<LocalDate> HORIZON = ForecastCalculator.horizon(TO.plusDays(1));

    private final WeekdayAverageStrategy strategy = new WeekdayAverageStrategy();

    @Test
    void shouldExpectTheAverageOfTheSameWeekdayPerCategory() {
        Map<LocalDate, BigDecimal> cash = new HashMap<>();
        Map<LocalDate, BigDecimal> card = new HashMap<>();
        for (LocalDate date : days(FROM, TO)) {
            if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                cash.put(date, new BigDecimal("100000.00"));
            }
            if (date.getDayOfWeek() == DayOfWeek.TUESDAY) {
                card.put(date, new BigDecimal("50000.00"));
            }
        }

        InflowEstimate estimate = strategy.estimate(history(days(FROM, TO), cash, card), HORIZON, PARAMETERS);

        assertThat(estimate.status()).isEqualTo(InflowEstimateStatus.ESTIMATED);
        assertThat(estimate.byDate()).hasSize(84);
        assertThat(estimate.samplesPerWeekday().values()).containsOnly(8);
        ExpectedInflow monday = estimate.on(LocalDate.of(2026, 10, 19)).orElseThrow();
        assertThat(monday.cashSalesDeposits()).isEqualByComparingTo("100000.00");
        assertThat(monday.cardSalesSettlements()).isEqualByComparingTo("0.00");
        ExpectedInflow tuesday = estimate.on(LocalDate.of(2026, 10, 20)).orElseThrow();
        assertThat(tuesday.cashSalesDeposits()).isEqualByComparingTo("0.00");
        assertThat(tuesday.cardSalesSettlements()).isEqualByComparingTo("50000.00");
        assertThat(estimate.on(LocalDate.of(2026, 10, 21)).orElseThrow().total()).isEqualByComparingTo("0.00");
    }

    // A covered day without deposits is a real zero and lowers the average.
    @Test
    void shouldCountCoveredDaysWithoutTransactionsAsZero() {
        Map<LocalDate, BigDecimal> cash = Map.of(
                LocalDate.of(2026, 8, 24), new BigDecimal("1000.00"),
                LocalDate.of(2026, 8, 31), new BigDecimal("1000.00"));

        InflowEstimate estimate = strategy.estimate(history(days(FROM, TO), cash, Map.of()), HORIZON, PARAMETERS);

        // Two Mondays with 1000 out of eight covered Mondays.
        assertThat(estimate.on(LocalDate.of(2026, 10, 19)).orElseThrow().cashSalesDeposits())
                .isEqualByComparingTo("250.00");
    }

    // Days outside every imported statement are unknown, not zero.
    @Test
    void shouldAverageOnlyStatementCoveredDays() {
        Set<LocalDate> lastFourWeeks = days(LocalDate.of(2026, 9, 17), TO);
        Map<LocalDate, BigDecimal> cash = lastFourWeeks.stream()
                .filter(date -> date.getDayOfWeek() == DayOfWeek.MONDAY)
                .collect(Collectors.toMap(date -> date, date -> new BigDecimal("1000.00")));

        InflowEstimate estimate = strategy.estimate(history(lastFourWeeks, cash, Map.of()), HORIZON, PARAMETERS);

        assertThat(estimate.status()).isEqualTo(InflowEstimateStatus.ESTIMATED);
        assertThat(estimate.samplesPerWeekday().values()).containsOnly(4);
        assertThat(estimate.on(LocalDate.of(2026, 10, 19)).orElseThrow().cashSalesDeposits())
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void shouldNotEstimateWithTooFewCoveredDays() {
        Set<LocalDate> threeWeeks = days(LocalDate.of(2026, 9, 24), TO);
        Map<LocalDate, BigDecimal> cash = Map.of(LocalDate.of(2026, 10, 12), new BigDecimal("5000.00"));

        InflowEstimate estimate = strategy.estimate(history(threeWeeks, cash, Map.of()), HORIZON, PARAMETERS);

        assertThat(estimate.status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(estimate.byDate()).isEmpty();
        assertThat(estimate.on(LocalDate.of(2026, 10, 19))).isEmpty();
        assertThat(estimate.samplesPerWeekday().values()).containsOnly(3);
    }

    // Statements without Sundays: every weekday needs enough history, or nothing is estimated.
    @Test
    void shouldNotEstimateWhenOneWeekdayIsNeverCovered() {
        Set<LocalDate> noSundays = days(FROM, TO).stream()
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .collect(Collectors.toSet());

        InflowEstimate estimate = strategy.estimate(history(noSundays, Map.of(), Map.of()), HORIZON, PARAMETERS);

        assertThat(estimate.status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(estimate.samplesPerWeekday()).containsEntry(DayOfWeek.SUNDAY, 0).containsEntry(DayOfWeek.MONDAY, 8);
    }

    @Test
    void shouldIgnoreCoveredDaysOutsideTheWindow() {
        Set<LocalDate> covered = days(FROM.minusWeeks(4), TO);
        Map<LocalDate, BigDecimal> cash = Map.of(LocalDate.of(2026, 8, 10), new BigDecimal("8000000.00"));

        InflowEstimate estimate = strategy.estimate(history(covered, cash, Map.of()), HORIZON, PARAMETERS);

        assertThat(estimate.samplesPerWeekday().values()).containsOnly(8);
        assertThat(estimate.on(LocalDate.of(2026, 10, 19)).orElseThrow().total()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldRoundAveragesHalfUpToCents() {
        Set<LocalDate> fourWeeks = days(LocalDate.of(2026, 9, 17), TO);
        List<LocalDate> mondays = fourWeeks.stream().filter(date -> date.getDayOfWeek() == DayOfWeek.MONDAY)
                .sorted().toList();
        Map<LocalDate, BigDecimal> card = Map.of(
                mondays.get(0), new BigDecimal("10.00"), mondays.get(1), new BigDecimal("10.00"),
                mondays.get(2), new BigDecimal("10.01"), mondays.get(3), new BigDecimal("10.01"));

        InflowEstimate estimate = strategy.estimate(history(fourWeeks, Map.of(), card), HORIZON, PARAMETERS);

        // 40.02 / 4 = 10.005
        assertThat(estimate.on(LocalDate.of(2026, 10, 19)).orElseThrow().cardSalesSettlements())
                .isEqualTo(new BigDecimal("10.01"));
    }

    private static InflowHistory history(Set<LocalDate> covered, Map<LocalDate, BigDecimal> cash,
                                         Map<LocalDate, BigDecimal> card) {
        return new InflowHistory(FROM, TO, covered, cash, card);
    }

    private static Set<LocalDate> days(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).collect(Collectors.toSet());
    }
}
