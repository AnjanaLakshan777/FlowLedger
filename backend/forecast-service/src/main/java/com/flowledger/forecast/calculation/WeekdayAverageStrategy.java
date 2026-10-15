package com.flowledger.forecast.calculation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expects each future day to receive the average of what the same weekday received in the
 * lookback window, separately for cash deposits and card settlements. Only statement-covered
 * days are averaged. If any weekday has fewer covered days than the minimum, no inflows are
 * estimated at all rather than estimating from a few days or filling gaps with zero.
 */
@Component
public class WeekdayAverageStrategy implements InflowForecastStrategy {

    public static final String CODE = "WEEKDAY_AVERAGE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public InflowEstimate estimate(InflowHistory history, List<LocalDate> forecastDates, ForecastParameters parameters) {
        Map<DayOfWeek, Integer> samples = new EnumMap<>(DayOfWeek.class);
        Map<DayOfWeek, BigDecimal> cashTotals = new EnumMap<>(DayOfWeek.class);
        Map<DayOfWeek, BigDecimal> cardTotals = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            samples.put(day, 0);
            cashTotals.put(day, BigDecimal.ZERO);
            cardTotals.put(day, BigDecimal.ZERO);
        }

        for (LocalDate date : history.coveredDates()) {
            if (date.isBefore(history.from()) || date.isAfter(history.to())) {
                continue;
            }
            DayOfWeek day = date.getDayOfWeek();
            samples.merge(day, 1, Integer::sum);
            cashTotals.merge(day, history.cashSalesDepositsOn(date), BigDecimal::add);
            cardTotals.merge(day, history.cardSalesSettlementsOn(date), BigDecimal::add);
        }

        if (samples.values().stream().anyMatch(count -> count < parameters.minimumSamplesPerWeekday())) {
            return InflowEstimate.insufficient(samples);
        }

        Map<DayOfWeek, ExpectedInflow> perWeekday = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            BigDecimal count = BigDecimal.valueOf(samples.get(day));
            perWeekday.put(day, new ExpectedInflow(
                    cashTotals.get(day).divide(count, 2, RoundingMode.HALF_UP),
                    cardTotals.get(day).divide(count, 2, RoundingMode.HALF_UP)));
        }

        Map<LocalDate, ExpectedInflow> byDate = new LinkedHashMap<>();
        for (LocalDate date : forecastDates) {
            byDate.put(date, perWeekday.get(date.getDayOfWeek()));
        }
        return new InflowEstimate(InflowEstimateStatus.ESTIMATED, byDate, samples);
    }
}
