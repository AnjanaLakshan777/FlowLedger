package com.flowledger.forecast.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 15);
    private static final List<LocalDate> HORIZON = ForecastCalculator.horizon(START);

    @Test
    void shouldCoverExactlyEightyFourConsecutiveDays() {
        assertThat(HORIZON).hasSize(84);
        assertThat(HORIZON.getFirst()).isEqualTo(START);
        assertThat(HORIZON.getLast()).isEqualTo(LocalDate.of(2027, 1, 6));
        for (int i = 1; i < HORIZON.size(); i++) {
            assertThat(HORIZON.get(i)).isEqualTo(HORIZON.get(i - 1).plusDays(1));
        }
    }

    @Test
    void shouldRollTheBalanceForwardWithInflowsAndCheques() {
        InflowEstimate inflows = estimated(new ExpectedInflow(new BigDecimal("1000.00"), new BigDecimal("250.50")));
        List<ExpectedCheque> cheques = List.of(
                cheque("000123", "3000.00", START.plusDays(1)),
                cheque("000124", "499.50", START.plusDays(1)),
                cheque("000125", "100.00", START.plusDays(83)));

        List<DailyProjection> days = ForecastCalculator.project(new BigDecimal("10000.00"), HORIZON, inflows, cheques);

        assertThat(days).hasSize(84);
        assertThat(days.get(0).openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(days.get(0).closingBalance()).isEqualByComparingTo("11250.50");
        assertThat(days.get(1).openingBalance()).isEqualByComparingTo("11250.50");
        assertThat(days.get(1).chequeOutflows()).isEqualByComparingTo("3499.50");
        assertThat(days.get(1).chequeCount()).isEqualTo(2);
        assertThat(days.get(1).closingBalance()).isEqualByComparingTo("9001.50");
        for (int i = 1; i < days.size(); i++) {
            assertThat(days.get(i).openingBalance()).isEqualByComparingTo(days.get(i - 1).closingBalance());
        }
        // 10000 + 84 * 1250.50 - 3499.50 - 100
        assertThat(days.getLast().closingBalance()).isEqualByComparingTo("111442.50");
    }

    @Test
    void shouldMarkDaysBelowZeroAsShortageButNotZeroItself() {
        List<ExpectedCheque> cheques = List.of(
                cheque("000001", "1000.00", START),
                cheque("000002", "0.01", START.plusDays(1)));

        List<DailyProjection> days = ForecastCalculator.project(
                new BigDecimal("1000.00"), HORIZON, estimated(zero()), cheques);

        assertThat(days.get(0).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(days.get(0).cashShortage()).isFalse();
        assertThat(days.get(1).closingBalance()).isEqualByComparingTo("-0.01");
        assertThat(days.get(1).cashShortage()).isTrue();
        assertThat(days.getLast().cashShortage()).isTrue();
    }

    @Test
    void shouldStartFromANegativeConfirmedBalance() {
        List<DailyProjection> days = ForecastCalculator.project(
                new BigDecimal("-5000.00"), HORIZON, estimated(zero()), List.of());

        assertThat(days.get(0).openingBalance()).isEqualByComparingTo("-5000.00");
        assertThat(days).allMatch(DailyProjection::cashShortage);
    }

    // Without an inflow estimate the balance moves only by cheques, and inflows stay unknown.
    @Test
    void shouldLeaveInflowsOutWhenTheyCannotBeEstimated() {
        InflowEstimate none = InflowEstimate.insufficient(new EnumMap<>(DayOfWeek.class));

        List<DailyProjection> days = ForecastCalculator.project(new BigDecimal("500.00"), HORIZON, none,
                List.of(cheque("000777", "200.00", START.plusDays(2))));

        assertThat(days).allMatch(day -> day.inflow() == null);
        assertThat(days.get(2).closingBalance()).isEqualByComparingTo("300.00");
        assertThat(days.getLast().closingBalance()).isEqualByComparingTo("300.00");
    }

    private static InflowEstimate estimated(ExpectedInflow everyDay) {
        Map<LocalDate, ExpectedInflow> byDate = new LinkedHashMap<>();
        HORIZON.forEach(date -> byDate.put(date, everyDay));
        return new InflowEstimate(InflowEstimateStatus.ESTIMATED, byDate, Map.of());
    }

    private static ExpectedInflow zero() {
        return new ExpectedInflow(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static ExpectedCheque cheque(String number, String amount, LocalDate clearing) {
        return new ExpectedCheque(UUID.randomUUID(), number, new BigDecimal(amount), clearing, 1);
    }
}
