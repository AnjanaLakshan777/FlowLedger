package com.flowledger.forecast.dto;

import com.flowledger.forecast.entity.ForecastDailyResult;
import com.flowledger.forecast.entity.ForecastRun;
import com.flowledger.forecast.entity.ForecastRunCheque;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A forecast run with its 84 days and the cheques it deducted. Expected inflows are null when the
 * run could not estimate them.
 */
public record ForecastRunResponse(
        ForecastRunSummaryResponse run,
        Totals totals,
        List<Day> days,
        List<Cheque> cheques
) {

    public record Day(
            LocalDate date,
            BigDecimal expectedCashSalesDeposits,
            BigDecimal expectedCardSalesSettlements,
            BigDecimal expectedSalesInflows,
            BigDecimal expectedChequeOutflows,
            int expectedChequeCount,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            boolean cashShortage
    ) {

        static Day from(ForecastDailyResult result) {
            return new Day(
                    result.getForecastDate(),
                    result.getExpectedCashSalesDeposits(),
                    result.getExpectedCardSalesSettlements(),
                    result.getExpectedSalesInflows(),
                    result.getExpectedChequeOutflows(),
                    result.getExpectedChequeCount(),
                    result.getOpeningBalance(),
                    result.getClosingBalance(),
                    result.isCashShortage()
            );
        }
    }

    public record Cheque(UUID chequeId, String chequeNumber, BigDecimal amount, LocalDate expectedClearingDate,
                         String statusAtRun, int sourceVersion) {

        static Cheque from(ForecastRunCheque cheque) {
            return new Cheque(cheque.getChequeId(), cheque.getChequeNumber(), cheque.getAmount(),
                    cheque.getExpectedClearingDate(), cheque.getStatusAtRun(), cheque.getSourceVersion());
        }
    }

    /**
     * @param firstShortageDate first day whose closing balance is below LKR 0, or null
     * @param shortageDays      number of such days
     */
    public record Totals(
            BigDecimal expectedSalesInflows,
            BigDecimal expectedChequeOutflows,
            BigDecimal closingBalance,
            BigDecimal lowestClosingBalance,
            LocalDate lowestBalanceDate,
            LocalDate firstShortageDate,
            int shortageDays
    ) {

        static Totals of(List<ForecastDailyResult> days) {
            boolean inflowsEstimated = days.stream().allMatch(day -> day.getExpectedSalesInflows() != null);
            BigDecimal inflows = inflowsEstimated
                    ? sum(days.stream().map(ForecastDailyResult::getExpectedSalesInflows).toList())
                    : null;
            ForecastDailyResult lowest = days.stream()
                    .min(Comparator.comparing(ForecastDailyResult::getClosingBalance))
                    .orElseThrow();
            return new Totals(
                    inflows,
                    sum(days.stream().map(ForecastDailyResult::getExpectedChequeOutflows).toList()),
                    days.getLast().getClosingBalance(),
                    lowest.getClosingBalance(),
                    lowest.getForecastDate(),
                    days.stream().filter(ForecastDailyResult::isCashShortage)
                            .map(ForecastDailyResult::getForecastDate).findFirst().orElse(null),
                    (int) days.stream().filter(ForecastDailyResult::isCashShortage).count()
            );
        }

        private static BigDecimal sum(List<BigDecimal> amounts) {
            return amounts.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public static ForecastRunResponse from(ForecastRun run, List<ForecastDailyResult> days,
                                           List<ForecastRunCheque> cheques) {
        return new ForecastRunResponse(
                ForecastRunSummaryResponse.from(run),
                Totals.of(days),
                days.stream().map(Day::from).toList(),
                cheques.stream().map(Cheque::from).toList()
        );
    }
}
