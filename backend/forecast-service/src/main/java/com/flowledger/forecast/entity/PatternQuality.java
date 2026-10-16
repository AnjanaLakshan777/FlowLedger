package com.flowledger.forecast.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How a PREVIOUS_YEAR_PATTERN forecast was made, stored with the run. Observations are counted per
 * channel (cash, card) and day.
 *
 * @param referenceOffsetDays         days between a date and its previous-year reference (364: same weekday)
 * @param growthWindowFrom            first current-year day of the growth window
 * @param growthWindowTo              last day of the growth window (the balance date)
 * @param comparableCashDays          growth-window days with a known current-year and previous-year cash value
 * @param comparableCardDays          the same for card settlements
 * @param cashGrowthRatio             current-year over previous-year cash collections, rounded to 4 places
 *                                    for display (the forecast uses the exact ratio); null when not computed
 * @param cardGrowthRatio             the same for card settlements
 * @param cashRatioOutOfRange         whether the exact cash ratio lies outside the warning range
 * @param cardRatioOutOfRange         the same for card settlements
 * @param referenceFromBank           previous-year reference observations (growth window and horizon) taken
 *                                    from bank-confirmed transactions
 * @param referenceFromImports        those taken from approved historical sales imports
 * @param missingReferenceDays        horizon days whose previous-year reference is unknown in either channel
 * @param insufficientReason          why no inflows were estimated, or null
 */
public record PatternQuality(
        String method,
        int referenceOffsetDays,
        LocalDate growthWindowFrom,
        LocalDate growthWindowTo,
        int comparableCashDays,
        int comparableCardDays,
        BigDecimal cashGrowthRatio,
        BigDecimal cardGrowthRatio,
        BigDecimal growthRatioWarningLow,
        BigDecimal growthRatioWarningHigh,
        boolean cashRatioOutOfRange,
        boolean cardRatioOutOfRange,
        int referenceFromBank,
        int referenceFromImports,
        int missingReferenceDays,
        int cashReceiptLagDays,
        int cardReceiptLagDays,
        String insufficientReason
) {

    public static final String NOT_ENOUGH_COMPARABLE_DAYS = "NOT_ENOUGH_COMPARABLE_DAYS";
    public static final String MISSING_REFERENCE_DAYS = "MISSING_REFERENCE_DAYS";
    public static final String ZERO_REFERENCE_COLLECTIONS = "ZERO_REFERENCE_COLLECTIONS";
}
