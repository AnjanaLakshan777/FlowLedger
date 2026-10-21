package com.flowledger.forecast.dto;

import com.flowledger.forecast.calculation.InflowEstimateStatus;
import com.flowledger.forecast.entity.DataQuality;
import com.flowledger.forecast.entity.ForecastRun;
import com.flowledger.forecast.entity.PatternQuality;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A data-quality label shown with a forecast. {@code code} is stable for clients; the message is
 * for people.
 */
public record ForecastWarning(String code, String message) {

    public static final String BALANCE_STALE = "BALANCE_STALE";
    public static final String INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY";
    public static final String INCOMPLETE_CLASSIFICATION = "INCOMPLETE_CLASSIFICATION";
    public static final String ACTUALS_INCOMPLETE = "ACTUALS_INCOMPLETE";
    public static final String PATTERN_FROM_SALES_HISTORY = "PATTERN_FROM_SALES_HISTORY";
    public static final String GROWTH_RATIO_OUT_OF_RANGE = "GROWTH_RATIO_OUT_OF_RANGE";
    public static final String GROWTH_FROM_SALES_RECORDS = "GROWTH_FROM_SALES_RECORDS";

    public static List<ForecastWarning> forRun(ForecastRun run) {
        DataQuality quality = run.getDataQuality();
        List<ForecastWarning> warnings = new ArrayList<>();
        if (run.isBalanceStale()) {
            warnings.add(new ForecastWarning(BALANCE_STALE, "The confirmed bank balance is "
                    + quality.balanceAgeDays() + " days old. Import a recent bank statement."));
        }
        PatternQuality pattern = quality.pattern();
        if (run.getInflowEstimateStatus() == InflowEstimateStatus.INSUFFICIENT_HISTORY) {
            warnings.add(new ForecastWarning(INSUFFICIENT_HISTORY, pattern == null
                    ? "Not enough imported bank history to estimate sales inflows (at least "
                    + quality.minimumDaysPerWeekday() + " statement days of every weekday are needed). "
                    + "Balances include cheque payments only."
                    : insufficientPattern(pattern, quality.minimumDaysPerWeekday())));
        }
        if (pattern != null && pattern.referenceFromImports() > 0) {
            warnings.add(new ForecastWarning(PATTERN_FROM_SALES_HISTORY, "Part of last year's pattern comes from "
                    + "recorded sales (sale date plus " + pattern.cashReceiptLagDays() + " day(s) for cash, "
                    + pattern.cardReceiptLagDays() + " for card) where no bank statement covers the day."));
        }
        if (pattern != null && pattern.currentFromSales() > 0) {
            warnings.add(new ForecastWarning(GROWTH_FROM_SALES_RECORDS, "This year's growth is partly measured from "
                    + "recorded daily sales (sale date plus " + pattern.cashReceiptLagDays() + " day(s) for cash, "
                    + pattern.cardReceiptLagDays() + " for card) where no bank statement covers the day. Import bank "
                    + "statements to measure it from bank-confirmed deposits."));
        }
        if (pattern != null && (pattern.cashRatioOutOfRange() || pattern.cardRatioOutOfRange())) {
            warnings.add(new ForecastWarning(GROWTH_RATIO_OUT_OF_RANGE, "This year's collections compared with last "
                    + "year (cash " + ratio(pattern.cashGrowthRatio()) + ", card " + ratio(pattern.cardGrowthRatio())
                    + ") are outside " + pattern.growthRatioWarningLow().toPlainString() + "–"
                    + pattern.growthRatioWarningHigh().toPlainString() + ". The forecast uses them as they are; check "
                    + "the history for gaps or one-off events."));
        }
        if (quality.unclassifiedCreditCount() > 0) {
            warnings.add(unclassified(quality.unclassifiedCreditCount(), quality.unclassifiedCreditAmount(),
                    "Expected sales inflows may be understated."));
        }
        return warnings;
    }

    private static String insufficientPattern(PatternQuality pattern, int minimumDays) {
        String reason = switch (String.valueOf(pattern.insufficientReason())) {
            case PatternQuality.MISSING_REFERENCE_DAYS -> pattern.missingReferenceDays()
                    + " day(s) of last year's corresponding weeks have no bank statement and no recorded sales";
            case PatternQuality.ZERO_REFERENCE_COLLECTIONS -> "last year's collections in the comparison weeks were zero, "
                    + "so this year's growth cannot be measured";
            default -> "fewer than " + minimumDays + " days of every weekday in the last "
                    + "weeks (bank statements or recorded daily sales) can be compared with the same weekday a year "
                    + "earlier";
        };
        return "Sales inflows were not estimated: " + reason + ". Balances include cheque payments only.";
    }

    private static String ratio(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }

    public static ForecastWarning unclassified(int count, BigDecimal amount, String consequence) {
        return new ForecastWarning(INCOMPLETE_CLASSIFICATION, count + " bank transaction(s) totalling LKR "
                + amount.toPlainString() + " are not classified yet. " + consequence);
    }
}
