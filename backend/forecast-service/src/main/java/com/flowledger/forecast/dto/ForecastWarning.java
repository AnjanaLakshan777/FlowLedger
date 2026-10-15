package com.flowledger.forecast.dto;

import com.flowledger.forecast.calculation.InflowEstimateStatus;
import com.flowledger.forecast.entity.DataQuality;
import com.flowledger.forecast.entity.ForecastRun;

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

    public static List<ForecastWarning> forRun(ForecastRun run) {
        DataQuality quality = run.getDataQuality();
        List<ForecastWarning> warnings = new ArrayList<>();
        if (run.isBalanceStale()) {
            warnings.add(new ForecastWarning(BALANCE_STALE, "The confirmed bank balance is "
                    + quality.balanceAgeDays() + " days old. Import a recent bank statement."));
        }
        if (run.getInflowEstimateStatus() == InflowEstimateStatus.INSUFFICIENT_HISTORY) {
            warnings.add(new ForecastWarning(INSUFFICIENT_HISTORY, "Not enough imported bank history to estimate "
                    + "sales inflows (at least " + quality.minimumDaysPerWeekday() + " statement days of every "
                    + "weekday are needed). Balances include cheque payments only."));
        }
        if (quality.unclassifiedCreditCount() > 0) {
            warnings.add(unclassified(quality.unclassifiedCreditCount(), quality.unclassifiedCreditAmount(),
                    "Expected sales inflows may be understated."));
        }
        return warnings;
    }

    public static ForecastWarning unclassified(int count, BigDecimal amount, String consequence) {
        return new ForecastWarning(INCOMPLETE_CLASSIFICATION, count + " bank transaction(s) totalling LKR "
                + amount.toPlainString() + " are not classified yet. " + consequence);
    }
}
