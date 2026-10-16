package com.flowledger.forecast.calculation;

import com.flowledger.forecast.calculation.PreviousYearPatternStrategy.ApprovedSale;
import com.flowledger.forecast.calculation.PreviousYearPatternStrategy.Inputs;
import com.flowledger.forecast.calculation.PreviousYearPatternStrategy.Result;
import com.flowledger.forecast.entity.PatternQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class PreviousYearPatternStrategyTest {

    private static final LocalDate BALANCE_DATE = LocalDate.of(2026, 10, 14);
    private static final ForecastParameters PARAMETERS = ForecastParameters.PREVIOUS_YEAR_DEFAULTS;
    private static final UUID BATCH = UUID.randomUUID();

    // Weekday alignment

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-01-01, 2025-01-02",
            "2025-01-02, 2024-01-04",
            "2025-01-01, 2024-01-03",
            "2024-01-01, 2023-01-02",
            "2026-12-31, 2026-01-01",
            "2025-03-01, 2024-03-02",
            "2024-03-01, 2023-03-03",
            "2025-02-28, 2024-03-01",
            "2025-02-27, 2024-02-29"
    })
    void shouldUseTheSameWeekday52WeeksEarlier(LocalDate date, LocalDate expected) {
        LocalDate reference = PreviousYearPatternStrategy.referenceDate(date);

        assertThat(reference).isEqualTo(expected);
        assertThat(reference.getDayOfWeek()).isEqualTo(date.getDayOfWeek());
    }

    @Test
    void shouldMatchTheJanuaryFirstExample() {
        LocalDate forecastDate = LocalDate.of(2026, 1, 1);
        LocalDate previous = PreviousYearPatternStrategy.referenceDate(forecastDate);
        LocalDate older = PreviousYearPatternStrategy.referenceDate(previous);

        assertThat(forecastDate.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(previous).isEqualTo(LocalDate.of(2025, 1, 2)).isNotEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(older).isEqualTo(LocalDate.of(2024, 1, 4)).isNotEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(previous.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(older.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-14", "2025-12-15", "2024-02-10", "2023-12-31", "2027-11-20"})
    void shouldAlignEveryHorizonDayAcrossYearEndsAndLeapDays(String balance) {
        List<LocalDate> horizon = ForecastCalculator.horizon(LocalDate.parse(balance).plusDays(1));

        assertThat(horizon).hasSize(84);
        assertThat(horizon).allSatisfy(date -> {
            assertThat(PreviousYearPatternStrategy.referenceDate(date).getDayOfWeek()).isEqualTo(date.getDayOfWeek());
            assertThat(PreviousYearPatternStrategy.referenceDate(date)).isEqualTo(date.minusWeeks(52));
        });
    }

    // Growth adjustment

    @Test
    void shouldScaleLastYearsPatternByEachChannelsOwnGrowth() {
        Fixture fixture = new Fixture()
                .currentYear(day -> "1100.00", day -> "3000.00")
                .previousYearBank(day -> weekdayAmount(day, 300), day -> "2000.00");

        Result result = fixture.estimate();

        assertThat(result.estimate().status()).isEqualTo(InflowEstimateStatus.ESTIMATED);
        PatternQuality quality = result.quality();
        // Cash: 1100 a day this year against a weekday pattern of 300..2100 last year (x 7700/8400);
        // card: 3000 against 2000 (x1.5).
        assertThat(quality.cardGrowthRatio()).isEqualByComparingTo("1.5000");
        assertThat(quality.cashGrowthRatio()).isEqualByComparingTo(new BigDecimal("1100").multiply(BigDecimal.valueOf(7))
                .divide(BigDecimal.valueOf(300 * 28), 4, RoundingMode.HALF_UP));
        LocalDate first = horizon().getFirst();
        ExpectedInflow expected = result.estimate().on(first).orElseThrow();
        BigDecimal cashReference = new BigDecimal(weekdayAmount(PreviousYearPatternStrategy.referenceDate(first), 300));
        assertThat(expected.cashSalesDeposits()).isEqualByComparingTo(cashReference.multiply(new BigDecimal("7700"))
                .divide(new BigDecimal("8400"), 2, RoundingMode.HALF_UP));
        assertThat(expected.cardSalesSettlements()).isEqualByComparingTo("3000.00");
        assertThat(result.estimate().byDate()).hasSize(84);
        assertThat(quality.comparableCashDays()).isEqualTo(56);
        assertThat(quality.referenceFromBank()).isEqualTo(2 * 56 + 2 * 84);
        assertThat(quality.referenceFromImports()).isZero();
        assertThat(quality.cashRatioOutOfRange()).isFalse();
    }

    @Test
    void shouldRoundOnceHalfUpToCents() {
        // Ratio 1/8 = 0.125: a reference of 1.00 becomes 0.125, rounded half-up to 0.13 (not 0.12).
        Result result = new Fixture()
                .currentYear(day -> "1.00", day -> "1.00")
                .previousYearBank(day -> "8.00", day -> "1.00")
                .horizonReferences(day -> "1.00", day -> "1.00")
                .estimate();

        ExpectedInflow expected = result.estimate().on(horizon().getFirst()).orElseThrow();
        assertThat(expected.cashSalesDeposits()).isEqualTo(new BigDecimal("0.13"));
        assertThat(expected.cardSalesSettlements()).isEqualTo(new BigDecimal("1.00"));
        assertThat(result.quality().cashGrowthRatio()).isEqualTo(new BigDecimal("0.1250"));
    }

    @Test
    void shouldReportButNotCapAnExtremeRatio() {
        Result result = new Fixture()
                .currentYear(day -> "2500.00", day -> "400.00")
                .previousYearBank(day -> "1000.00", day -> "1000.00")
                .estimate();

        ExpectedInflow expected = result.estimate().on(horizon().getFirst()).orElseThrow();
        assertThat(expected.cashSalesDeposits()).isEqualByComparingTo("2500.00");
        assertThat(expected.cardSalesSettlements()).isEqualByComparingTo("400.00");
        assertThat(result.quality().cashRatioOutOfRange()).isTrue();
        assertThat(result.quality().cardRatioOutOfRange()).isTrue();
    }

    @Test
    void shouldTreatTheWarningBoundsAsInRange() {
        Result result = new Fixture()
                .currentYear(day -> "2000.00", day -> "500.00")
                .previousYearBank(day -> "1000.00", day -> "1000.00")
                .estimate();

        assertThat(result.quality().cashRatioOutOfRange()).isFalse();
        assertThat(result.quality().cardRatioOutOfRange()).isFalse();
    }

    // Insufficient history

    @Test
    void shouldNotEstimateWithZeroPreviousYearCollectionsButNonZeroReferences() {
        Result result = new Fixture()
                .currentYear(day -> "100.00", day -> "100.00")
                .previousYearBank(day -> "0.00", day -> "100.00")
                .horizonReferences(day -> "50.00", day -> "100.00")
                .estimate();

        assertThat(result.estimate().status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(result.estimate().byDate()).isEmpty();
        assertThat(result.quality().insufficientReason()).isEqualTo(PatternQuality.ZERO_REFERENCE_COLLECTIONS);
        assertThat(result.quality().cashGrowthRatio()).isNull();
    }

    @Test
    void shouldExpectZeroForAChannelThatHadNoCollectionsAtAll() {
        Result result = new Fixture()
                .currentYear(day -> "0.00", day -> "100.00")
                .previousYearBank(day -> "0.00", day -> "100.00")
                .estimate();

        assertThat(result.estimate().status()).isEqualTo(InflowEstimateStatus.ESTIMATED);
        assertThat(result.estimate().on(horizon().getFirst()).orElseThrow().cashSalesDeposits()).isEqualByComparingTo("0");
        assertThat(result.quality().cashGrowthRatio()).isNull();
    }

    @Test
    void shouldNotEstimateWithTooFewComparableDaysOfAWeekday() {
        Fixture fixture = new Fixture()
                .currentYear(day -> "100.00", day -> "100.00")
                .previousYearBank(day -> "100.00", day -> "100.00");
        // Leave five of the eight Mondays of this year's window uncovered: three comparable Mondays remain.
        growthWindow().stream().filter(day -> day.getDayOfWeek() == DayOfWeek.MONDAY).limit(5)
                .forEach(fixture.covered::remove);

        Result result = fixture.estimate();

        assertThat(result.estimate().status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(result.quality().insufficientReason()).isEqualTo(PatternQuality.NOT_ENOUGH_COMPARABLE_DAYS);
        assertThat(result.estimate().samplesPerWeekday()).containsEntry(DayOfWeek.MONDAY, 3);
    }

    @Test
    void shouldLeaveUnknownDaysOutOfTheRatioInsteadOfReadingThemAsZero() {
        Fixture fixture = new Fixture()
                .currentYear(day -> "200.00", day -> "200.00")
                .previousYearBank(day -> "100.00", day -> "100.00");
        // Two weeks of this year unknown: the ratio stays 2, it is not diluted by zeros.
        growthWindow().stream().limit(14).forEach(fixture.covered::remove);

        Result result = fixture.estimate();

        assertThat(result.quality().cashGrowthRatio()).isEqualByComparingTo("2.0000");
        assertThat(result.quality().comparableCashDays()).isEqualTo(42);
    }

    @Test
    void shouldNotEstimateWhenAHorizonDaysReferenceIsUnknown() {
        Fixture fixture = new Fixture()
                .currentYear(day -> "100.00", day -> "100.00")
                .previousYearBank(day -> "100.00", day -> "100.00");
        fixture.covered.remove(PreviousYearPatternStrategy.referenceDate(horizon().get(10)));

        Result result = fixture.estimate();

        assertThat(result.estimate().status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(result.quality().insufficientReason()).isEqualTo(PatternQuality.MISSING_REFERENCE_DAYS);
        assertThat(result.quality().missingReferenceDays()).isEqualTo(1);
    }

    // Approved historical sales

    @Test
    void shouldUseApprovedSalesWithTheReceiptLagWhereNoStatementCoversTheDay() {
        ForecastParameters lags = new ForecastParameters(PreviousYearPatternStrategy.CODE, 8, 4, 3, 1, 3,
                new BigDecimal("0.5"), new BigDecimal("2.0"));
        Fixture fixture = new Fixture()
                .currentYear(day -> "200.00", day -> "200.00")
                .previousYearSales(sale -> new String[]{"100.00", "100.00"});
        // A day well inside the horizon, so the changed sales below touch no growth-window day.
        LocalDate first = horizon().get(40);
        LocalDate reference = PreviousYearPatternStrategy.referenceDate(first);
        // Cash is received one day after the sale, card three days after.
        fixture.sales.put(reference.minusDays(1), sale(reference.minusDays(1), "40.00", "999.00"));
        fixture.sales.put(reference.minusDays(3), sale(reference.minusDays(3), "999.00", "60.00"));

        Result result = fixture.estimate(lags);

        ExpectedInflow expected = result.estimate().on(first).orElseThrow();
        assertThat(expected.cashSalesDeposits()).isEqualByComparingTo("80.00");
        assertThat(expected.cardSalesSettlements()).isEqualByComparingTo("120.00");
        assertThat(result.quality().referenceFromImports()).isPositive();
        assertThat(result.quality().referenceFromBank()).isZero();
        assertThat(result.quality().cardReceiptLagDays()).isEqualTo(3);
    }

    @Test
    void shouldPreferBankFiguresOverImportedSalesForTheSameDayAndNeverAddThem() {
        Fixture fixture = new Fixture()
                .currentYear(day -> "200.00", day -> "200.00")
                .previousYearBank(day -> "100.00", day -> "100.00")
                // An approved import covers the same receipt days with different figures.
                .previousYearSales(sale -> new String[]{"5000.00", "5000.00"});

        Result result = fixture.estimate();

        ExpectedInflow expected = result.estimate().on(horizon().getFirst()).orElseThrow();
        // 100 x 2, not (100 + 5000) x 2 or 5000 x 2.
        assertThat(expected.cashSalesDeposits()).isEqualByComparingTo("200.00");
        assertThat(expected.cardSalesSettlements()).isEqualByComparingTo("200.00");
        assertThat(result.quality().referenceFromImports()).isZero();
    }

    @Test
    void shouldTreatAnApprovedZeroDayAsKnownAndAMissingDayAsUnknown() {
        Fixture fixture = new Fixture()
                .currentYear(day -> "200.00", day -> "200.00")
                .previousYearSales(sale -> new String[]{"100.00", "100.00"});
        LocalDate zeroDay = horizon().get(5);
        LocalDate zeroSale = PreviousYearPatternStrategy.referenceDate(zeroDay).minusDays(1);
        fixture.sales.put(zeroSale, sale(zeroSale, "0.00", "0.00"));

        Result known = fixture.estimate();
        assertThat(known.estimate().status()).isEqualTo(InflowEstimateStatus.ESTIMATED);
        assertThat(known.estimate().on(zeroDay).orElseThrow().total()).isEqualByComparingTo("0");

        fixture.sales.remove(zeroSale);
        Result unknown = fixture.estimate();
        assertThat(unknown.estimate().status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(unknown.quality().missingReferenceDays()).isEqualTo(1);
    }

    @Test
    void shouldMeasureGrowthOnlyAgainstBankConfirmedCurrentYearCollections() {
        // Sales imported for this year's window do not count as this year's collections.
        Fixture fixture = new Fixture()
                .previousYearBank(day -> "100.00", day -> "100.00");
        growthWindow().forEach(day -> fixture.sales.put(day.minusDays(1), sale(day.minusDays(1), "900.00", "900.00")));

        Result result = fixture.estimate();

        assertThat(result.estimate().status()).isEqualTo(InflowEstimateStatus.INSUFFICIENT_HISTORY);
        assertThat(result.quality().comparableCashDays()).isZero();
    }

    // Helpers

    private static List<LocalDate> horizon() {
        return ForecastCalculator.horizon(BALANCE_DATE.plusDays(1));
    }

    private static List<LocalDate> growthWindow() {
        return PreviousYearPatternStrategy.growthWindowStart(BALANCE_DATE, 8).datesUntil(BALANCE_DATE.plusDays(1)).toList();
    }

    private static String weekdayAmount(LocalDate day, int step) {
        return BigDecimal.valueOf((long) day.getDayOfWeek().getValue() * step).setScale(2).toPlainString();
    }

    private static ApprovedSale sale(LocalDate date, String cash, String card) {
        return new ApprovedSale(date, new BigDecimal(cash), new BigDecimal(card), BATCH);
    }

    /** Builds inputs: covered days with bank credits this year and last year, and approved sales. */
    private static final class Fixture {

        final Set<LocalDate> covered = new HashSet<>();
        final Map<LocalDate, BigDecimal> cash = new HashMap<>();
        final Map<LocalDate, BigDecimal> card = new HashMap<>();
        final Map<LocalDate, ApprovedSale> sales = new HashMap<>();

        Fixture currentYear(Function<LocalDate, String> cashOn, Function<LocalDate, String> cardOn) {
            growthWindow().forEach(day -> bank(day, cashOn, cardOn));
            return this;
        }

        // Last year's growth-window and horizon reference days, covered by statements.
        Fixture previousYearBank(Function<LocalDate, String> cashOn, Function<LocalDate, String> cardOn) {
            growthWindow().forEach(day -> bank(PreviousYearPatternStrategy.referenceDate(day), cashOn, cardOn));
            horizon().forEach(day -> bank(PreviousYearPatternStrategy.referenceDate(day), cashOn, cardOn));
            return this;
        }

        Fixture horizonReferences(Function<LocalDate, String> cashOn, Function<LocalDate, String> cardOn) {
            horizon().forEach(day -> bank(PreviousYearPatternStrategy.referenceDate(day), cashOn, cardOn));
            return this;
        }

        // Approved sales whose receipt (sale date + 1 day) is each reference day.
        Fixture previousYearSales(Function<LocalDate, String[]> amounts) {
            List<LocalDate> receipts = new ArrayList<>();
            growthWindow().forEach(day -> receipts.add(PreviousYearPatternStrategy.referenceDate(day)));
            horizon().forEach(day -> receipts.add(PreviousYearPatternStrategy.referenceDate(day)));
            for (LocalDate receipt : receipts) {
                LocalDate saleDate = receipt.minusDays(1);
                String[] values = amounts.apply(saleDate);
                sales.put(saleDate, sale(saleDate, values[0], values[1]));
            }
            return this;
        }

        private void bank(LocalDate day, Function<LocalDate, String> cashOn, Function<LocalDate, String> cardOn) {
            covered.add(day);
            cash.put(day, new BigDecimal(cashOn.apply(day)));
            card.put(day, new BigDecimal(cardOn.apply(day)));
        }

        Result estimate() {
            return estimate(PARAMETERS);
        }

        Result estimate(ForecastParameters parameters) {
            return PreviousYearPatternStrategy.estimate(
                    new Inputs(BALANCE_DATE, horizon(), covered, cash, card, sales), parameters);
        }
    }
}
