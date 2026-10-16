package com.flowledger.forecast.calculation;

import com.flowledger.forecast.entity.PatternQuality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Option B: each forecast day receives what its previous-year reference day received, scaled by
 * how current-year collections compare with the previous year. Cash deposits and card settlements
 * are calculated separately.
 * <ul>
 *     <li>Reference day: {@code d - 364} days, exactly 52 weeks, so the weekday is the same.</li>
 *     <li>A previous-year value is bank-confirmed (CASH_SALES_DEPOSIT / CARD_SALES_SETTLEMENT by
 *     transaction date) when that day is covered by an imported statement; otherwise an approved
 *     historical sale whose sale date plus the channel's receipt lag is that day; otherwise
 *     unknown. Bank and import values are never added together.</li>
 *     <li>Growth ratio per channel: the sum of current-year bank-confirmed collections over the sum
 *     of previous-year values, over the growth-window days (lookbackWeeks before the balance date)
 *     where both values are known. Unknown days are left out, never read as zero.</li>
 *     <li>Expected(d) = reference(d - 364) x current sum / previous sum, rounded half-up to cents
 *     once. The ratio is not capped; a ratio outside the warning range is reported.</li>
 * </ul>
 * No inflows are estimated (INSUFFICIENT_HISTORY) when a weekday has fewer comparable days than the
 * minimum in either channel, when a horizon day's reference is unknown, or when previous-year
 * collections are zero while reference days are not. There is no fallback to another method.
 */
public final class PreviousYearPatternStrategy {

    public static final String CODE = "PREVIOUS_YEAR_PATTERN";
    public static final int REFERENCE_OFFSET_DAYS = 364;

    private enum Channel { CASH, CARD }

    private enum Source { BANK, IMPORT }

    private record Observation(BigDecimal amount, Source source) {
    }

    /** An approved historical sale (sale date, not a bank date) from cash-management-service. */
    public record ApprovedSale(LocalDate saleDate, BigDecimal cashSales, BigDecimal cardSales, UUID importBatchId) {
    }

    /**
     * @param coveredDates     days covered by an imported statement (current and previous year)
     * @param bankCash         CASH_SALES_DEPOSIT credits per covered date
     * @param bankCard         CARD_SALES_SETTLEMENT credits per covered date
     * @param salesBySaleDate  approved historical sales, one per sale date
     */
    public record Inputs(LocalDate balanceDate, List<LocalDate> forecastDates, Set<LocalDate> coveredDates,
                         Map<LocalDate, BigDecimal> bankCash, Map<LocalDate, BigDecimal> bankCard,
                         Map<LocalDate, ApprovedSale> salesBySaleDate) {
    }

    public record Result(InflowEstimate estimate, PatternQuality quality) {
    }

    private PreviousYearPatternStrategy() {
    }

    public static LocalDate referenceDate(LocalDate date) {
        return date.minusDays(REFERENCE_OFFSET_DAYS);
    }

    public static LocalDate growthWindowStart(LocalDate balanceDate, int weeks) {
        return balanceDate.minusWeeks(weeks).plusDays(1);
    }

    public static Result estimate(Inputs inputs, ForecastParameters parameters) {
        int cashLag = parameters.cashReceiptLagDays();
        int cardLag = parameters.cardReceiptLagDays();
        LocalDate windowFrom = growthWindowStart(inputs.balanceDate(), parameters.lookbackWeeks());

        Map<Channel, BigDecimal> currentSums = new EnumMap<>(Channel.class);
        Map<Channel, BigDecimal> previousSums = new EnumMap<>(Channel.class);
        Map<Channel, Map<DayOfWeek, Integer>> comparable = new EnumMap<>(Channel.class);
        int fromBank = 0;
        int fromImports = 0;
        for (Channel channel : Channel.values()) {
            currentSums.put(channel, BigDecimal.ZERO);
            previousSums.put(channel, BigDecimal.ZERO);
            Map<DayOfWeek, Integer> counts = new EnumMap<>(DayOfWeek.class);
            for (DayOfWeek day : DayOfWeek.values()) {
                counts.put(day, 0);
            }
            comparable.put(channel, counts);
        }

        for (LocalDate day = windowFrom; !day.isAfter(inputs.balanceDate()); day = day.plusDays(1)) {
            for (Channel channel : Channel.values()) {
                Optional<BigDecimal> current = currentYear(inputs, channel, day);
                Optional<Observation> previous = previousYear(inputs, channel, referenceDate(day), cashLag, cardLag);
                if (current.isEmpty() || previous.isEmpty()) {
                    continue;
                }
                currentSums.merge(channel, current.get(), BigDecimal::add);
                previousSums.merge(channel, previous.get().amount(), BigDecimal::add);
                comparable.get(channel).merge(day.getDayOfWeek(), 1, Integer::sum);
                if (previous.get().source() == Source.BANK) {
                    fromBank++;
                } else {
                    fromImports++;
                }
            }
        }

        Map<LocalDate, Map<Channel, BigDecimal>> references = new LinkedHashMap<>();
        int missing = 0;
        for (LocalDate date : inputs.forecastDates()) {
            Map<Channel, BigDecimal> values = new EnumMap<>(Channel.class);
            boolean dayMissing = false;
            for (Channel channel : Channel.values()) {
                Optional<Observation> reference = previousYear(inputs, channel, referenceDate(date), cashLag, cardLag);
                if (reference.isEmpty()) {
                    dayMissing = true;
                    continue;
                }
                values.put(channel, reference.get().amount());
                if (reference.get().source() == Source.BANK) {
                    fromBank++;
                } else {
                    fromImports++;
                }
            }
            references.put(date, values);
            if (dayMissing) {
                missing++;
            }
        }

        Map<DayOfWeek, Integer> samples = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            samples.put(day, Math.min(comparable.get(Channel.CASH).get(day), comparable.get(Channel.CARD).get(day)));
        }
        Quality quality = new Quality(parameters, windowFrom, inputs.balanceDate(), comparable, fromBank, fromImports,
                missing, currentSums, previousSums);

        if (samples.values().stream().anyMatch(count -> count < parameters.minimumSamplesPerWeekday())) {
            return new Result(InflowEstimate.insufficient(samples), quality.build(PatternQuality.NOT_ENOUGH_COMPARABLE_DAYS));
        }
        if (missing > 0) {
            return new Result(InflowEstimate.insufficient(samples), quality.build(PatternQuality.MISSING_REFERENCE_DAYS));
        }
        for (Channel channel : Channel.values()) {
            boolean referencesAllZero = references.values().stream().allMatch(v -> v.get(channel).signum() == 0);
            // Zero previous-year collections give no ratio; only all-zero reference days need none.
            if (previousSums.get(channel).signum() == 0 && !referencesAllZero) {
                return new Result(InflowEstimate.insufficient(samples),
                        quality.build(PatternQuality.ZERO_REFERENCE_COLLECTIONS));
            }
        }

        Map<LocalDate, ExpectedInflow> byDate = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, Map<Channel, BigDecimal>> entry : references.entrySet()) {
            byDate.put(entry.getKey(), new ExpectedInflow(
                    scaled(entry.getValue().get(Channel.CASH), currentSums.get(Channel.CASH), previousSums.get(Channel.CASH)),
                    scaled(entry.getValue().get(Channel.CARD), currentSums.get(Channel.CARD), previousSums.get(Channel.CARD))));
        }
        return new Result(new InflowEstimate(InflowEstimateStatus.ESTIMATED, byDate, samples), quality.build(null));
    }

    // reference x current / previous, rounded once; a zero reference stays zero (also when previous is 0).
    static BigDecimal scaled(BigDecimal reference, BigDecimal currentSum, BigDecimal previousSum) {
        if (reference.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return reference.multiply(currentSum).divide(previousSum, 2, RoundingMode.HALF_UP);
    }

    private static Optional<BigDecimal> currentYear(Inputs inputs, Channel channel, LocalDate day) {
        if (!inputs.coveredDates().contains(day)) {
            return Optional.empty();
        }
        return Optional.of(bank(inputs, channel, day));
    }

    // Bank-confirmed when the day has statement coverage; otherwise an approved sale received that
    // day after the channel's lag; otherwise unknown.
    private static Optional<Observation> previousYear(Inputs inputs, Channel channel, LocalDate day, int cashLag,
                                                      int cardLag) {
        if (inputs.coveredDates().contains(day)) {
            return Optional.of(new Observation(bank(inputs, channel, day), Source.BANK));
        }
        ApprovedSale sale = inputs.salesBySaleDate().get(day.minusDays(channel == Channel.CASH ? cashLag : cardLag));
        if (sale == null) {
            return Optional.empty();
        }
        return Optional.of(new Observation(channel == Channel.CASH ? sale.cashSales() : sale.cardSales(), Source.IMPORT));
    }

    private static BigDecimal bank(Inputs inputs, Channel channel, LocalDate day) {
        Map<LocalDate, BigDecimal> values = channel == Channel.CASH ? inputs.bankCash() : inputs.bankCard();
        return values.getOrDefault(day, BigDecimal.ZERO);
    }

    private record Quality(ForecastParameters parameters, LocalDate windowFrom, LocalDate windowTo,
                           Map<Channel, Map<DayOfWeek, Integer>> comparable, int fromBank, int fromImports,
                           int missing, Map<Channel, BigDecimal> currentSums, Map<Channel, BigDecimal> previousSums) {

        PatternQuality build(String insufficientReason) {
            return new PatternQuality(CODE, REFERENCE_OFFSET_DAYS, windowFrom, windowTo,
                    total(comparable.get(Channel.CASH)), total(comparable.get(Channel.CARD)),
                    ratio(Channel.CASH), ratio(Channel.CARD),
                    parameters.growthRatioWarningLow(), parameters.growthRatioWarningHigh(),
                    outOfRange(Channel.CASH), outOfRange(Channel.CARD),
                    fromBank, fromImports, missing, parameters.cashReceiptLagDays(), parameters.cardReceiptLagDays(),
                    insufficientReason);
        }

        private BigDecimal ratio(Channel channel) {
            BigDecimal previous = previousSums.get(channel);
            return previous.signum() == 0 ? null
                    : currentSums.get(channel).divide(previous, 4, RoundingMode.HALF_UP);
        }

        // Compared exactly: current < low x previous or current > high x previous.
        private boolean outOfRange(Channel channel) {
            BigDecimal previous = previousSums.get(channel);
            if (previous.signum() == 0) {
                return false;
            }
            BigDecimal current = currentSums.get(channel);
            return current.compareTo(parameters.growthRatioWarningLow().multiply(previous)) < 0
                    || current.compareTo(parameters.growthRatioWarningHigh().multiply(previous)) > 0;
        }

        private static int total(Map<DayOfWeek, Integer> counts) {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
