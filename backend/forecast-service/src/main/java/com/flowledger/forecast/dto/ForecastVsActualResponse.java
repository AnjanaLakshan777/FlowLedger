package com.flowledger.forecast.dto;

import com.flowledger.forecast.calculation.InflowEstimateStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A saved forecast run compared with what actually happened in the bank, by actual transaction
 * date. Actual values are null for days not covered by an imported statement (including future
 * days), never zero. Differences are actual minus forecast.
 *
 * @param comparedThrough last day with actual values, or null if none yet
 */
public record ForecastVsActualResponse(
        UUID forecastRunId,
        LocalDate horizonStartDate,
        LocalDate horizonEndDate,
        InflowEstimateStatus inflowEstimateStatus,
        LocalDate comparedThrough,
        Totals totals,
        List<Day> days,
        List<ForecastWarning> warnings
) {

    /**
     * @param actualSalesInflows        CASH_SALES_DEPOSIT and CARD_SALES_SETTLEMENT credits
     * @param actualChequeOutflows      CHEQUE_PAYMENT debits
     * @param actualClosingBalance      the forecast's opening balance plus every actual transaction since;
     *                                  null once a day without statement coverage breaks the chain
     * @param unclassifiedTransactions  UNCLASSIFIED transactions that day; they are in no comparison total
     */
    public record Day(
            LocalDate date,
            boolean actualsAvailable,
            BigDecimal forecastSalesInflows,
            BigDecimal actualSalesInflows,
            BigDecimal salesInflowDifference,
            BigDecimal forecastChequeOutflows,
            BigDecimal actualChequeOutflows,
            BigDecimal chequeOutflowDifference,
            BigDecimal forecastClosingBalance,
            BigDecimal actualClosingBalance,
            BigDecimal closingBalanceDifference,
            int unclassifiedTransactions
    ) {
    }

    // Over the days with actual values only.
    public record Totals(
            int daysCompared,
            BigDecimal forecastSalesInflows,
            BigDecimal actualSalesInflows,
            BigDecimal salesInflowDifference,
            BigDecimal forecastChequeOutflows,
            BigDecimal actualChequeOutflows,
            BigDecimal chequeOutflowDifference,
            int unclassifiedTransactionCount,
            BigDecimal unclassifiedTransactionAmount
    ) {
    }
}
