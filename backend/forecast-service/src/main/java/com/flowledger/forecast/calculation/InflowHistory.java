package com.flowledger.forecast.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Actual sales inflows from classified bank transactions, by actual transaction date. Only
 * {@code coveredDates} are known: a covered date without transactions had no inflow, while a
 * date outside every imported statement is unknown and must not be read as zero.
 *
 * @param from                 first day of the lookback window
 * @param to                   last day of the lookback window (the balance date)
 * @param coveredDates         days within the window covered by an imported statement
 * @param cashSalesDeposits    CASH_SALES_DEPOSIT credits per covered date
 * @param cardSalesSettlements CARD_SALES_SETTLEMENT credits per covered date
 */
public record InflowHistory(
        LocalDate from,
        LocalDate to,
        Set<LocalDate> coveredDates,
        Map<LocalDate, BigDecimal> cashSalesDeposits,
        Map<LocalDate, BigDecimal> cardSalesSettlements
) {

    public BigDecimal cashSalesDepositsOn(LocalDate date) {
        return cashSalesDeposits.getOrDefault(date, BigDecimal.ZERO);
    }

    public BigDecimal cardSalesSettlementsOn(LocalDate date) {
        return cardSalesSettlements.getOrDefault(date, BigDecimal.ZERO);
    }
}
