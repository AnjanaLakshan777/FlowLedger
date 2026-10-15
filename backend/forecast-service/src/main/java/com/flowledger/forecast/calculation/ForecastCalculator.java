package com.flowledger.forecast.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolls the confirmed opening balance forward one day at a time: each day's opening is the
 * previous day's closing, plus expected sales inflows, minus cheques expected to clear that day.
 * A day is a cash shortage when its closing balance is below LKR 0.
 */
public final class ForecastCalculator {

    public static final int HORIZON_DAYS = 84;

    private ForecastCalculator() {
    }

    public static List<LocalDate> horizon(LocalDate start) {
        return start.datesUntil(start.plusDays(HORIZON_DAYS)).toList();
    }

    public static List<DailyProjection> project(BigDecimal openingBalance, List<LocalDate> dates,
                                                InflowEstimate inflows, List<ExpectedCheque> cheques) {
        List<DailyProjection> days = new ArrayList<>(dates.size());
        BigDecimal balance = openingBalance;
        for (LocalDate date : dates) {
            List<ExpectedCheque> clearing = cheques.stream()
                    .filter(cheque -> cheque.expectedClearingDate().equals(date))
                    .toList();
            BigDecimal outflows = clearing.stream()
                    .map(ExpectedCheque::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            ExpectedInflow inflow = inflows.on(date).orElse(null);

            BigDecimal opening = balance;
            balance = opening.add(inflow == null ? BigDecimal.ZERO : inflow.total()).subtract(outflows);
            days.add(new DailyProjection(date, inflow, outflows, clearing.size(), opening, balance,
                    balance.signum() < 0));
        }
        return days;
    }
}
