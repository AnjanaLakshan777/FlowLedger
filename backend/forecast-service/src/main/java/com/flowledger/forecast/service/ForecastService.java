package com.flowledger.forecast.service;

import com.flowledger.forecast.calculation.DailyProjection;
import com.flowledger.forecast.calculation.ExpectedCheque;
import com.flowledger.forecast.calculation.ForecastCalculator;
import com.flowledger.forecast.calculation.ForecastParameters;
import com.flowledger.forecast.calculation.InflowEstimate;
import com.flowledger.forecast.calculation.InflowForecastStrategy;
import com.flowledger.forecast.calculation.InflowHistory;
import com.flowledger.forecast.calculation.PreviousYearPatternStrategy;
import com.flowledger.forecast.calculation.PreviousYearPatternStrategy.RecordedSale;
import com.flowledger.forecast.calculation.StatementCoverage;
import com.flowledger.forecast.client.BankIntegrationClient;
import com.flowledger.forecast.client.BankIntegrationClient.Balance;
import com.flowledger.forecast.client.BankIntegrationClient.Transaction;
import com.flowledger.forecast.client.CashManagementClient;
import com.flowledger.forecast.client.SourceCaller;
import com.flowledger.forecast.dto.ForecastRunResponse;
import com.flowledger.forecast.dto.ForecastRunSummaryResponse;
import com.flowledger.forecast.entity.DataQuality;
import com.flowledger.forecast.entity.ForecastModelConfig;
import com.flowledger.forecast.entity.ForecastRun;
import com.flowledger.forecast.entity.PatternQuality;
import com.flowledger.forecast.entity.TriggerType;
import com.flowledger.forecast.exception.BalanceNotAvailableException;
import com.flowledger.forecast.exception.ForecastRunNotFoundException;
import com.flowledger.forecast.repository.ForecastDailyResultRepository;
import com.flowledger.forecast.repository.ForecastModelConfigRepository;
import com.flowledger.forecast.repository.ForecastRunChequeRepository;
import com.flowledger.forecast.repository.ForecastRunRepository;
import com.flowledger.forecast.repository.ForecastRunStore;
import com.flowledger.forecast.security.CompanyAccessVerifier;
import com.flowledger.forecast.security.CompanyRole;
import com.flowledger.forecast.verification.VerifiedChequeProjection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Creates and reads rolling 84-day forecasts. A run starts from the latest confirmed bank balance
 * of the company's current account, adds sales inflows estimated by the configured method
 * (PREVIOUS_YEAR_PATTERN, or WEEKDAY_AVERAGE for older configurations) from classified bank history
 * and approved historical sales,
 * and deducts ISSUED cheques on their expected clearing dates. Bank inputs come from
 * bank-integration-service's API; cheques come from forecast's own projection of payment-service's
 * cheques, and only while that projection is verified complete (otherwise the forecast is refused).
 * All inputs are read before the database transaction; a run is saved complete and never changed.
 */
@Slf4j
@Service
public class ForecastService {

    static final String CASH_SALES_DEPOSIT = "CASH_SALES_DEPOSIT";
    static final String CARD_SALES_SETTLEMENT = "CARD_SALES_SETTLEMENT";
    static final String CHEQUE_PAYMENT = "CHEQUE_PAYMENT";
    static final String UNCLASSIFIED = "UNCLASSIFIED";
    static final String CREDIT = "CREDIT";
    static final String DEBIT = "DEBIT";

    // Viewing the forecast pages is open to these roles in the UI permission matrix; creating a
    // run only reads data, so it is allowed to the same roles.
    static final Set<CompanyRole> FORECAST_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);

    // The nil UUID marks something created by the service itself, not by a user (as in migration V6).
    static final UUID SYSTEM_ACTOR = new UUID(0, 0);
    private static final String MODEL_VERSION_PREFIX = "1:";
    private static final String PREVIOUS_YEAR_MODEL_VERSION_PREFIX = "2:";

    private final ForecastModelConfigRepository configRepository;
    private final ForecastRunRepository runRepository;
    private final ForecastDailyResultRepository dailyResultRepository;
    private final ForecastRunChequeRepository runChequeRepository;
    private final ForecastRunStore runStore;
    private final BankIntegrationClient bankIntegrationClient;
    private final CashManagementClient cashManagementClient;
    private final VerifiedChequeProjection chequeProjection;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Map<String, InflowForecastStrategy> strategies;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public record CreatedRun(boolean created, ForecastRunResponse forecast) {
    }

    public ForecastService(ForecastModelConfigRepository configRepository,
                           ForecastRunRepository runRepository,
                           ForecastDailyResultRepository dailyResultRepository,
                           ForecastRunChequeRepository runChequeRepository,
                           ForecastRunStore runStore,
                           BankIntegrationClient bankIntegrationClient,
                           CashManagementClient cashManagementClient,
                           VerifiedChequeProjection chequeProjection,
                           CompanyAccessVerifier companyAccessVerifier,
                           List<InflowForecastStrategy> strategies,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.configRepository = configRepository;
        this.runRepository = runRepository;
        this.dailyResultRepository = dailyResultRepository;
        this.runChequeRepository = runChequeRepository;
        this.runStore = runStore;
        this.bankIntegrationClient = bankIntegrationClient;
        this.cashManagementClient = cashManagementClient;
        this.chequeProjection = chequeProjection;
        this.companyAccessVerifier = companyAccessVerifier;
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(InflowForecastStrategy::code, Function.identity()));
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public CreatedRun createRun(Jwt user, UUID companyId) {
        companyAccessVerifier.verify(user, companyId, FORECAST_ROLES);
        UUID actor = UUID.fromString(user.getSubject());
        return create(SourceCaller.user(user), companyId, actor, TriggerType.MANUAL);
    }

    /**
     * The daily scheduled forecast for a company, made without a user: inputs come from the other
     * services' internal APIs, and the run is SCHEDULED with no triggering user. The same rules and
     * the same-inputs check apply, so a day whose inputs did not change gives no new run.
     */
    public CreatedRun createScheduledRun(UUID companyId) {
        return create(SourceCaller.SERVICE, companyId, null, TriggerType.SCHEDULED);
    }

    // actor: the user, or null for the service itself (a configuration it creates is then by the nil UUID).
    private CreatedRun create(SourceCaller caller, UUID companyId, UUID actor, TriggerType trigger) {
        ForecastModelConfig config = activeConfig(companyId, actor == null ? SYSTEM_ACTOR : actor);
        ForecastParameters parameters = config.getParameters();
        boolean previousYear = PreviousYearPatternStrategy.CODE.equals(parameters.strategy());
        InflowForecastStrategy strategy = strategies.get(parameters.strategy());
        if (!previousYear && strategy == null) {
            throw new IllegalStateException("Unknown forecast strategy " + parameters.strategy());
        }

        // Inputs, read before any transaction so no connection is held while other services answer.
        UUID accountId = bankIntegrationClient.findActiveCurrentAccount(caller, companyId);
        Balance balance = bankIntegrationClient.findLatestBalance(caller, companyId, accountId)
                .filter(found -> "LKR".equals(found.currency()))
                .orElseThrow(BalanceNotAvailableException::new);

        Instant asOf = clock.instant();
        LocalDate today = LocalDate.now(clock);
        LocalDate balanceDate = LocalDate.ofInstant(balance.balanceAsOf(), clock.getZone());
        List<LocalDate> horizon = ForecastCalculator.horizon(balanceDate.plusDays(1));

        List<StatementCoverage.Period> periods = importedPeriods(caller, companyId, accountId);
        Inflows inflows = previousYear
                ? previousYearInflows(caller, companyId, accountId, balanceDate, horizon, periods, parameters)
                : weekdayInflows(caller, companyId, accountId, balanceDate, horizon, periods, parameters, strategy);
        List<ExpectedCheque> cheques = chequeProjection
                .issuedCheques(companyId, accountId, horizon.getFirst(), horizon.getLast()).stream()
                .sorted(Comparator.comparing(ExpectedCheque::expectedClearingDate)
                        .thenComparing(ExpectedCheque::chequeNumber))
                .toList();

        InflowEstimate estimate = inflows.estimate();
        List<DailyProjection> days = ForecastCalculator.project(balance.balanceAmount(), horizon, estimate, cheques);

        long balanceAgeDays = ChronoUnit.DAYS.between(balanceDate, today);
        List<Transaction> unclassifiedCredits = inflows.transactions().stream()
                .filter(transaction -> CREDIT.equals(transaction.direction())
                        && UNCLASSIFIED.equals(transaction.category()))
                .toList();
        DataQuality quality = new DataQuality(inflows.historyFrom(), balanceDate, inflows.coveredDays(),
                weekdayCounts(estimate.samplesPerWeekday()), parameters.minimumSamplesPerWeekday(),
                unclassifiedCredits.size(), sum(unclassifiedCredits),
                balanceAgeDays, parameters.balanceStaleAfterDays(), inflows.pattern());

        String modelVersion = previousYear
                ? PREVIOUS_YEAR_MODEL_VERSION_PREFIX + PreviousYearPatternStrategy.CODE
                : MODEL_VERSION_PREFIX + strategy.code();
        String inputHash = inputHash(modelVersion, config, accountId, balance, today, periods, inflows.transactions(),
                cheques, inflows.extraHashInput());
        ForecastRunStore.NewRun newRun = new ForecastRunStore.NewRun(companyId, config.getId(), accountId, asOf,
                horizon.getFirst(), horizon.getLast(), balance.balanceAmount(), balance.balanceAsOf(), "LKR",
                balanceAgeDays > parameters.balanceStaleAfterDays(), estimate.status(), quality, modelVersion,
                config.snapshot(), inputHash, trigger, actor, clock.instant());

        UUID createdId = writeTransaction.execute(status -> {
            Optional<UUID> runId = runStore.insertRun(newRun);
            runId.ifPresent(id -> {
                runStore.insertDays(id, days);
                runStore.insertCheques(id, cheques);
            });
            return runId.orElse(null);
        });

        if (createdId != null) {
            log.info("Forecast run {} created: inflows {}, {} cheques", createdId, estimate.status(), cheques.size());
            return new CreatedRun(true, loadRun(companyId, createdId));
        }
        // Same inputs as an existing run today: return that run instead of saving a copy.
        UUID existingId = readTransaction.execute(status -> runRepository
                .findByCompanyIdAndInputSnapshotHash(companyId, inputHash)
                .map(ForecastRun::getId)
                .orElseThrow());
        return new CreatedRun(false, loadRun(companyId, existingId));
    }

    public List<ForecastRunSummaryResponse> listRuns(Jwt user, UUID companyId) {
        companyAccessVerifier.verify(user, companyId, FORECAST_ROLES);
        return readTransaction.execute(status -> runRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .map(ForecastRunSummaryResponse::from)
                .toList());
    }

    public ForecastRunResponse getRun(Jwt user, UUID companyId, UUID runId) {
        companyAccessVerifier.verify(user, companyId, FORECAST_ROLES);
        return loadRun(companyId, runId);
    }

    private ForecastRunResponse loadRun(UUID companyId, UUID runId) {
        return readTransaction.execute(status -> {
            ForecastRun run = runRepository.findByCompanyIdAndId(companyId, runId)
                    .orElseThrow(ForecastRunNotFoundException::new);
            return ForecastRunResponse.from(run,
                    dailyResultRepository.findByForecastRunIdOrderByForecastDate(runId),
                    runChequeRepository.findByForecastRunIdOrderByExpectedClearingDateAscChequeNumberAsc(runId));
        });
    }

    // The first run of a company creates its default configuration; two at once create it once.
    private ForecastModelConfig activeConfig(UUID companyId, UUID actor) {
        Optional<ForecastModelConfig> existing = readTransaction.execute(
                status -> configRepository.findByCompanyIdAndActiveTrue(companyId));
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return writeTransaction.execute(
                    status -> configRepository.saveAndFlush(ForecastModelConfig.defaults(companyId, actor)));
        } catch (DataIntegrityViolationException e) {
            return readTransaction.execute(
                    status -> configRepository.findByCompanyIdAndActiveTrue(companyId).orElseThrow());
        }
    }

    List<StatementCoverage.Period> importedPeriods(SourceCaller caller, UUID companyId, UUID accountId) {
        return bankIntegrationClient.listStatementImports(caller, companyId).stream()
                .filter(batch -> batch.bankAccountId().equals(accountId) && batch.imported())
                .map(batch -> new StatementCoverage.Period(batch.periodStart(), batch.periodEnd()))
                .sorted(Comparator.comparing(StatementCoverage.Period::start).thenComparing(StatementCoverage.Period::end))
                .toList();
    }

    /**
     * The inflow estimate with what it was based on: the bank transactions read (for the unclassified
     * label and the digest), where that history starts, how many of its days a statement covers,
     * PREVIOUS_YEAR_PATTERN details, and digest lines beyond the common inputs.
     */
    private record Inflows(InflowEstimate estimate, List<Transaction> transactions, LocalDate historyFrom,
                           int coveredDays, PatternQuality pattern, String extraHashInput) {
    }

    // WEEKDAY_AVERAGE, unchanged: the lookback weeks of bank history before the balance date.
    private Inflows weekdayInflows(SourceCaller caller, UUID companyId, UUID accountId, LocalDate balanceDate,
                                   List<LocalDate> horizon, List<StatementCoverage.Period> periods,
                                   ForecastParameters parameters, InflowForecastStrategy strategy) {
        LocalDate historyFrom = balanceDate.minusWeeks(parameters.lookbackWeeks()).plusDays(1);
        List<Transaction> history = bankIntegrationClient.listTransactions(
                caller, companyId, accountId, historyFrom, balanceDate);
        Set<LocalDate> covered = StatementCoverage.coveredDates(periods, historyFrom, balanceDate);
        InflowEstimate estimate = strategy.estimate(
                inflowHistory(historyFrom, balanceDate, covered, history), horizon, parameters);
        return new Inflows(estimate, history, historyFrom, covered.size(), null, "");
    }

    /**
     * PREVIOUS_YEAR_PATTERN: the growth window of bank history before the balance date, the
     * previous-year bank history from 364 days before the growth window to 364 days before the
     * horizon end, and recorded sales (approved imports and daily sales entered by hand) whose
     * receipt (sale date plus lag) falls between the start of that previous-year range and the
     * balance date, so they serve days without statement coverage in both years.
     */
    private Inflows previousYearInflows(SourceCaller caller, UUID companyId, UUID accountId, LocalDate balanceDate,
                                        List<LocalDate> horizon, List<StatementCoverage.Period> periods,
                                        ForecastParameters parameters) {
        LocalDate windowFrom = PreviousYearPatternStrategy.growthWindowStart(balanceDate, parameters.lookbackWeeks());
        LocalDate referenceFrom = PreviousYearPatternStrategy.referenceDate(windowFrom);
        LocalDate referenceTo = PreviousYearPatternStrategy.referenceDate(horizon.getLast());
        int cashLag = parameters.cashReceiptLagDays();
        int cardLag = parameters.cardReceiptLagDays();

        List<Transaction> transactions = new ArrayList<>(bankIntegrationClient.listTransactions(
                caller, companyId, accountId, windowFrom, balanceDate));
        transactions.addAll(bankIntegrationClient.listTransactions(
                caller, companyId, accountId, referenceFrom, referenceTo));
        List<RecordedSale> sales = cashManagementClient.recordedSales(caller, companyId,
                referenceFrom.minusDays(Math.max(cashLag, cardLag)), balanceDate.minusDays(Math.min(cashLag, cardLag)));

        Set<LocalDate> covered = StatementCoverage.coveredDates(periods, referenceFrom, balanceDate);
        InflowHistory bank = inflowHistory(referenceFrom, balanceDate, covered, transactions);
        Map<LocalDate, RecordedSale> salesByDate = new HashMap<>();
        sales.forEach(sale -> salesByDate.put(sale.saleDate(), sale));
        PreviousYearPatternStrategy.Result result = PreviousYearPatternStrategy.estimate(
                new PreviousYearPatternStrategy.Inputs(balanceDate, horizon, covered, bank.cashSalesDeposits(),
                        bank.cardSalesSettlements(), salesByDate), parameters);

        StringBuilder extra = new StringBuilder()
                .append("pattern=").append(PreviousYearPatternStrategy.REFERENCE_OFFSET_DAYS).append('|')
                .append(parameters.lookbackWeeks()).append('|').append(parameters.minimumSamplesPerWeekday()).append('|')
                .append(cashLag).append('|').append(cardLag).append('|')
                .append(parameters.growthRatioWarningLow().toPlainString()).append('|')
                .append(parameters.growthRatioWarningHigh().toPlainString()).append('\n');
        sales.stream()
                .sorted(Comparator.comparing(RecordedSale::saleDate))
                .forEach(sale -> extra.append("sale=").append(sale.saleDate()).append('|')
                        .append(sale.cashSales().toPlainString()).append('|').append(sale.cardSales().toPlainString())
                        .append('|').append(sale.importBatchId()).append('\n'));
        return new Inflows(result.estimate(), transactions, referenceFrom, covered.size(), result.quality(),
                extra.toString());
    }

    private static InflowHistory inflowHistory(LocalDate from, LocalDate to, Set<LocalDate> covered,
                                               List<Transaction> transactions) {
        Map<LocalDate, BigDecimal> cash = new HashMap<>();
        Map<LocalDate, BigDecimal> card = new HashMap<>();
        for (Transaction transaction : transactions) {
            if (!CREDIT.equals(transaction.direction()) || !covered.contains(transaction.transactionDate())) {
                continue;
            }
            if (CASH_SALES_DEPOSIT.equals(transaction.category())) {
                cash.merge(transaction.transactionDate(), transaction.amount(), BigDecimal::add);
            } else if (CARD_SALES_SETTLEMENT.equals(transaction.category())) {
                card.merge(transaction.transactionDate(), transaction.amount(), BigDecimal::add);
            }
        }
        return new InflowHistory(from, to, covered, cash, card);
    }

    private static Map<DayOfWeek, Integer> weekdayCounts(Map<DayOfWeek, Integer> counts) {
        Map<DayOfWeek, Integer> ordered = new EnumMap<>(DayOfWeek.class);
        ordered.putAll(counts);
        return ordered;
    }

    static BigDecimal sum(List<Transaction> transactions) {
        return transactions.stream().map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Digest of everything the run depends on. The day is included because the stale label
     * depends on it; any change to the balance, statements, transactions (including their
     * category) or cheques gives a different digest and therefore a new run.
     */
    private static String inputHash(String modelVersion, ForecastModelConfig config, UUID accountId, Balance balance,
                                    LocalDate today, List<StatementCoverage.Period> periods,
                                    List<Transaction> transactions, List<ExpectedCheque> cheques, String extra) {
        StringBuilder input = new StringBuilder()
                .append("model=").append(modelVersion).append('\n')
                .append("config=").append(config.getId()).append(':').append(config.getVersion()).append('\n')
                .append("account=").append(accountId).append('\n')
                .append("balance=").append(balance.balanceAmount().toPlainString())
                .append('@').append(balance.balanceAsOf()).append('\n')
                .append("today=").append(today).append('\n');
        periods.forEach(period -> input.append("statement=").append(period.start()).append('/')
                .append(period.end()).append('\n'));
        transactions.stream()
                .sorted(Comparator.comparing(Transaction::id))
                .forEach(transaction -> input.append("transaction=").append(transaction.id()).append('|')
                        .append(transaction.transactionDate()).append('|').append(transaction.direction()).append('|')
                        .append(transaction.category()).append('|').append(transaction.amount().toPlainString())
                        .append('\n'));
        cheques.stream()
                .sorted(Comparator.comparing(ExpectedCheque::chequeId))
                .forEach(cheque -> input.append("cheque=").append(cheque.chequeId()).append('|')
                        .append(cheque.sourceVersion()).append('|').append(cheque.expectedClearingDate()).append('|')
                        .append(cheque.amount().toPlainString()).append('\n'));
        // Empty for WEEKDAY_AVERAGE, so its digests are exactly what they were.
        input.append(extra);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
