package com.flowledger.forecast.service;

import com.flowledger.forecast.calculation.InflowEstimateStatus;
import com.flowledger.forecast.calculation.StatementCoverage;
import com.flowledger.forecast.client.BankIntegrationClient;
import com.flowledger.forecast.client.BankIntegrationClient.Transaction;
import com.flowledger.forecast.client.SourceCaller;
import com.flowledger.forecast.dto.ForecastVsActualResponse;
import com.flowledger.forecast.dto.ForecastVsActualResponse.Day;
import com.flowledger.forecast.dto.ForecastVsActualResponse.Totals;
import com.flowledger.forecast.dto.ForecastWarning;
import com.flowledger.forecast.entity.ForecastDailyResult;
import com.flowledger.forecast.entity.ForecastRun;
import com.flowledger.forecast.exception.ForecastRunNotFoundException;
import com.flowledger.forecast.repository.ForecastDailyResultRepository;
import com.flowledger.forecast.repository.ForecastRunRepository;
import com.flowledger.forecast.security.CompanyAccessVerifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.flowledger.forecast.service.ForecastService.CARD_SALES_SETTLEMENT;
import static com.flowledger.forecast.service.ForecastService.CASH_SALES_DEPOSIT;
import static com.flowledger.forecast.service.ForecastService.CHEQUE_PAYMENT;
import static com.flowledger.forecast.service.ForecastService.CREDIT;
import static com.flowledger.forecast.service.ForecastService.DEBIT;
import static com.flowledger.forecast.service.ForecastService.FORECAST_ROLES;
import static com.flowledger.forecast.service.ForecastService.UNCLASSIFIED;

/**
 * Compares a saved forecast run with actual bank transactions by their actual transaction date.
 * Actual inflows are CASH_SALES_DEPOSIT and CARD_SALES_SETTLEMENT credits; actual outflows are
 * CHEQUE_PAYMENT debits. OTHER and UNCLASSIFIED transactions are in neither total. Which run is
 * the baseline for a period is not decided yet, so the client chooses the run.
 */
@Service
public class ForecastVsActualService {

    private final ForecastRunRepository runRepository;
    private final ForecastDailyResultRepository dailyResultRepository;
    private final ForecastService forecastService;
    private final BankIntegrationClient bankIntegrationClient;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Clock clock;
    private final TransactionTemplate readTransaction;

    public ForecastVsActualService(ForecastRunRepository runRepository,
                                   ForecastDailyResultRepository dailyResultRepository,
                                   ForecastService forecastService,
                                   BankIntegrationClient bankIntegrationClient,
                                   CompanyAccessVerifier companyAccessVerifier,
                                   Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.dailyResultRepository = dailyResultRepository;
        this.forecastService = forecastService;
        this.bankIntegrationClient = bankIntegrationClient;
        this.companyAccessVerifier = companyAccessVerifier;
        this.clock = clock;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    private record SavedRun(ForecastRun run, List<ForecastDailyResult> days) {
    }

    public ForecastVsActualResponse compare(Jwt user, UUID companyId, UUID runId) {
        companyAccessVerifier.verify(user, companyId, FORECAST_ROLES);
        SourceCaller caller = SourceCaller.user(user);
        SavedRun saved = readTransaction.execute(status -> {
            ForecastRun run = runRepository.findByCompanyIdAndId(companyId, runId)
                    .orElseThrow(ForecastRunNotFoundException::new);
            return new SavedRun(run, dailyResultRepository.findByForecastRunIdOrderByForecastDate(runId));
        });
        ForecastRun run = saved.run();

        // Bank transactions are imported once a month, after the month ends, so the current month has
        // no actuals yet: the comparison runs up to the end of last month.
        LocalDate actualsThrough = actualsThrough(LocalDate.now(clock));
        LocalDate start = run.getHorizonStartDate();
        LocalDate end = run.getHorizonEndDate().isBefore(actualsThrough) ? run.getHorizonEndDate() : actualsThrough;

        // Before the horizon has started there is nothing to compare, and nothing to fetch.
        boolean started = !end.isBefore(start);
        Set<LocalDate> covered = started
                ? StatementCoverage.coveredDates(
                        forecastService.importedPeriods(caller, companyId, run.getBankAccountId()), start, end)
                : Set.of();
        Map<LocalDate, List<Transaction>> byDate = started
                ? bankIntegrationClient.listTransactions(caller, companyId, run.getBankAccountId(), start, end).stream()
                        .collect(Collectors.groupingBy(Transaction::transactionDate))
                : Map.of();

        List<Day> days = new ArrayList<>();
        BigDecimal actualBalance = run.getOpeningBalanceAmount();
        for (ForecastDailyResult forecast : saved.days()) {
            LocalDate date = forecast.getForecastDate();
            if (!covered.contains(date)) {
                // Unknown day: no actuals, and the balance chain cannot continue past it.
                actualBalance = null;
                days.add(new Day(date, false, forecast.getExpectedSalesInflows(), null, null,
                        forecast.getExpectedChequeOutflows(), null, null, forecast.getClosingBalance(), null, null, 0));
                continue;
            }
            List<Transaction> transactions = byDate.getOrDefault(date, List.of());
            BigDecimal inflows = ForecastService.sum(transactions.stream()
                    .filter(transaction -> CREDIT.equals(transaction.direction()))
                    .filter(transaction -> CASH_SALES_DEPOSIT.equals(transaction.category())
                            || CARD_SALES_SETTLEMENT.equals(transaction.category()))
                    .toList());
            BigDecimal outflows = ForecastService.sum(transactions.stream()
                    .filter(transaction -> DEBIT.equals(transaction.direction())
                            && CHEQUE_PAYMENT.equals(transaction.category()))
                    .toList());
            if (actualBalance != null) {
                actualBalance = actualBalance.add(net(transactions));
            }
            int unclassified = (int) transactions.stream()
                    .filter(transaction -> UNCLASSIFIED.equals(transaction.category()))
                    .count();
            days.add(new Day(date, true,
                    forecast.getExpectedSalesInflows(), inflows, difference(inflows, forecast.getExpectedSalesInflows()),
                    forecast.getExpectedChequeOutflows(), outflows, outflows.subtract(forecast.getExpectedChequeOutflows()),
                    forecast.getClosingBalance(), actualBalance, difference(actualBalance, forecast.getClosingBalance()),
                    unclassified));
        }

        List<Transaction> unclassified = byDate.values().stream()
                .flatMap(List::stream)
                .filter(transaction -> covered.contains(transaction.transactionDate())
                        && UNCLASSIFIED.equals(transaction.category()))
                .toList();
        Totals totals = totals(days, run.getInflowEstimateStatus(), unclassified);
        return new ForecastVsActualResponse(run.getId(), start, run.getHorizonEndDate(), run.getInflowEstimateStatus(),
                actualsThrough,
                days.stream().filter(Day::actualsAvailable).map(Day::date).reduce((first, last) -> last).orElse(null),
                totals, days, warnings(run, days, actualsThrough, unclassified));
    }

    /** The last day that can have actuals: the end of the month before today's. */
    public static LocalDate actualsThrough(LocalDate today) {
        return today.withDayOfMonth(1).minusDays(1);
    }

    private static Totals totals(List<Day> days, InflowEstimateStatus inflowStatus, List<Transaction> unclassified) {
        List<Day> compared = days.stream().filter(Day::actualsAvailable).toList();
        BigDecimal forecastInflows = inflowStatus == InflowEstimateStatus.ESTIMATED
                ? total(compared.stream().map(Day::forecastSalesInflows).toList())
                : null;
        BigDecimal actualInflows = total(compared.stream().map(Day::actualSalesInflows).toList());
        BigDecimal forecastOutflows = total(compared.stream().map(Day::forecastChequeOutflows).toList());
        BigDecimal actualOutflows = total(compared.stream().map(Day::actualChequeOutflows).toList());
        return new Totals(compared.size(), forecastInflows, actualInflows, difference(actualInflows, forecastInflows),
                forecastOutflows, actualOutflows, actualOutflows.subtract(forecastOutflows),
                unclassified.size(), ForecastService.sum(unclassified));
    }

    private static List<ForecastWarning> warnings(ForecastRun run, List<Day> days, LocalDate actualsThrough,
                                                  List<Transaction> unclassified) {
        List<ForecastWarning> warnings = new ArrayList<>();
        if (run.getInflowEstimateStatus() == InflowEstimateStatus.INSUFFICIENT_HISTORY) {
            warnings.add(new ForecastWarning(ForecastWarning.INSUFFICIENT_HISTORY,
                    "This forecast has no estimated sales inflows, so inflows cannot be compared."));
        }
        // The current month is left out: its bank transactions are imported after it ends.
        long missingPastDays = days.stream()
                .filter(day -> !day.actualsAvailable() && !day.date().isAfter(actualsThrough))
                .count();
        if (missingPastDays > 0) {
            warnings.add(new ForecastWarning(ForecastWarning.ACTUALS_INCOMPLETE, missingPastDays
                    + " day(s) up to the end of last month have no imported bank transactions and no actual values."));
        }
        if (!unclassified.isEmpty()) {
            warnings.add(ForecastWarning.unclassified(unclassified.size(), ForecastService.sum(unclassified),
                    "They are not counted as sales inflows or cheque payments."));
        }
        return warnings;
    }

    private static BigDecimal net(List<Transaction> transactions) {
        BigDecimal net = BigDecimal.ZERO;
        for (Transaction transaction : transactions) {
            net = CREDIT.equals(transaction.direction())
                    ? net.add(transaction.amount())
                    : net.subtract(transaction.amount());
        }
        return net;
    }

    private static BigDecimal total(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal difference(BigDecimal actual, BigDecimal forecast) {
        return actual == null || forecast == null ? null : actual.subtract(forecast);
    }
}
