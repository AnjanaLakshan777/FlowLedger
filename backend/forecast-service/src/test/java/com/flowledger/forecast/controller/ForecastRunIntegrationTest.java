package com.flowledger.forecast.controller;

import com.flowledger.forecast.support.FakeIdentityServer;
import com.flowledger.forecast.support.FakeSourceServices;
import com.flowledger.forecast.support.FixedClockConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.flowledger.forecast.support.FakeSourceServices.cheque;
import static com.flowledger.forecast.support.FakeSourceServices.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Forecast runs from the inputs of bank-integration-service and payment-service, which are
 * served by FakeSourceServices over HTTP. "Today" is 15 October 2026. Needs the local forecast
 * PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
@Transactional
class ForecastRunIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeSourceServices sources = FakeSourceServices.instance();
    private static final String USER_TOKEN = "user-access-token";
    // End of 14 October 2026 in Colombo.
    private static final String BALANCE_AS_OF = "2026-10-14T18:29:59Z";

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
        registry.add("flowledger.bank-integration.base-url", sources::baseUrl);
        registry.add("flowledger.payment.base-url", sources::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID companyId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        identityService.reset();
        sources.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        sources.currentAccount(companyId, accountId);
        sources.balance(companyId, accountId, "500000.00", BALANCE_AS_OF);
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14));
        sources.transactions(companyId, salesHistory());
        sources.cheques(companyId, List.of());
    }

    @Test
    void shouldForecastEightyFourDaysFromTheConfirmedBalance() throws Exception {
        UUID cancelled = UUID.randomUUID();
        UUID beforeHorizon = UUID.randomUUID();
        UUID afterHorizon = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();
        sources.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000123", "250000.00", LocalDate.of(2026, 10, 20), "ISSUED"),
                cheque(UUID.randomUUID(), accountId, "000124", "100000.00", LocalDate.of(2026, 10, 20), "ISSUED"),
                cheque(UUID.randomUUID(), accountId, "000200", "75000.00", LocalDate.of(2027, 1, 6), "ISSUED"),
                cheque(cancelled, accountId, "000125", "90000.00", LocalDate.of(2026, 10, 21), "CANCELLED"),
                cheque(beforeHorizon, accountId, "000099", "40000.00", LocalDate.of(2026, 10, 14), "ISSUED"),
                cheque(afterHorizon, accountId, "000300", "60000.00", LocalDate.of(2027, 1, 7), "ISSUED"),
                cheque(otherAccount, UUID.randomUUID(), "000400", "30000.00", LocalDate.of(2026, 10, 22), "ISSUED")));

        String body = create()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.run.bankAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.run.runStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.run.triggerType").value("MANUAL"))
                .andExpect(jsonPath("$.run.triggeredBy").value(userId.toString()))
                .andExpect(jsonPath("$.run.openingBalance").value(500000.0))
                .andExpect(jsonPath("$.run.openingBalanceAsOf").value(BALANCE_AS_OF))
                .andExpect(jsonPath("$.run.currency").value("LKR"))
                .andExpect(jsonPath("$.run.horizonStartDate").value("2026-10-15"))
                .andExpect(jsonPath("$.run.horizonEndDate").value("2027-01-06"))
                .andExpect(jsonPath("$.run.balanceStale").value(false))
                .andExpect(jsonPath("$.run.inflowEstimateStatus").value("ESTIMATED"))
                .andExpect(jsonPath("$.run.dataQuality.historyFrom").value("2026-08-20"))
                .andExpect(jsonPath("$.run.dataQuality.historyTo").value("2026-10-14"))
                .andExpect(jsonPath("$.run.dataQuality.statementCoveredDays").value(56))
                .andExpect(jsonPath("$.run.dataQuality.coveredDaysPerWeekday.MONDAY").value(8))
                .andExpect(jsonPath("$.run.dataQuality.balanceAgeDays").value(1))
                .andExpect(jsonPath("$.run.config.methodCode").value("HISTORICAL_BANK_CASH_FLOW_PATTERN"))
                .andExpect(jsonPath("$.run.config.strategy").value("WEEKDAY_AVERAGE"))
                .andExpect(jsonPath("$.run.config.horizonDays").value(84))
                .andExpect(jsonPath("$.days", hasSize(84)))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-15"))
                .andExpect(jsonPath("$.days[0].openingBalance").value(500000.0))
                .andExpect(jsonPath("$.days[0].closingBalance").value(500000.0))
                .andExpect(jsonPath("$.days[83].date").value("2027-01-06"))
                // Monday 19 October: the average Monday cash deposit.
                .andExpect(jsonPath("$.days[4].date").value("2026-10-19"))
                .andExpect(jsonPath("$.days[4].expectedCashSalesDeposits").value(100000.0))
                .andExpect(jsonPath("$.days[4].expectedCardSalesSettlements").value(0.0))
                .andExpect(jsonPath("$.days[4].closingBalance").value(600000.0))
                // Tuesday 20 October: card settlements in, two cheques out.
                .andExpect(jsonPath("$.days[5].expectedCardSalesSettlements").value(50000.0))
                .andExpect(jsonPath("$.days[5].expectedSalesInflows").value(50000.0))
                .andExpect(jsonPath("$.days[5].expectedChequeOutflows").value(350000.0))
                .andExpect(jsonPath("$.days[5].expectedChequeCount").value(2))
                .andExpect(jsonPath("$.days[5].closingBalance").value(300000.0))
                .andExpect(jsonPath("$.days[83].expectedChequeOutflows").value(75000.0))
                .andExpect(jsonPath("$.totals.expectedSalesInflows").value(1800000.0))
                .andExpect(jsonPath("$.totals.expectedChequeOutflows").value(425000.0))
                .andExpect(jsonPath("$.totals.closingBalance").value(1875000.0))
                .andExpect(jsonPath("$.totals.lowestClosingBalance").value(300000.0))
                .andExpect(jsonPath("$.totals.lowestBalanceDate").value("2026-10-20"))
                .andExpect(jsonPath("$.totals.firstShortageDate").value(nullValue()))
                .andExpect(jsonPath("$.totals.shortageDays").value(0))
                .andExpect(jsonPath("$.cheques[*].chequeNumber").value(contains("000123", "000124", "000200")))
                .andExpect(jsonPath("$.cheques[*].statusAtRun").value(everyItem(is("ISSUED"))))
                .andReturn().getResponse().getContentAsString();

        UUID runId = UUID.fromString(JsonPath.read(body, "$.run.id"));
        assertThat(count("SELECT count(*) FROM forecast_daily_results WHERE forecast_run_id = ?", runId)).isEqualTo(84);
        assertThat(count("SELECT count(*) FROM forecast_run_cheques WHERE forecast_run_id = ?", runId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM forecast_run_cheques WHERE cheque_id IN (?, ?, ?, ?)",
                cancelled, beforeHorizon, afterHorizon, otherAccount)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT input_snapshot_hash FROM forecast_runs WHERE id = ?",
                String.class, runId)).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldAskTheSourceServicesForExactlyTheNeededDataWithTheUsersToken() throws Exception {
        create().andExpect(status().isCreated());

        List<String> requests = sources.requests();
        Map<String, String> transactionQuery = FakeSourceServices.params(requests.stream()
                .filter(request -> request.startsWith(FakeSourceServices.transactionsPath(companyId))).findFirst()
                .orElseThrow());
        assertThat(transactionQuery).containsEntry("bankAccountId", accountId.toString())
                .containsEntry("from", "2026-08-20").containsEntry("to", "2026-10-14");
        Map<String, String> chequeQuery = FakeSourceServices.params(requests.stream()
                .filter(request -> request.startsWith(FakeSourceServices.chequesPath(companyId))).findFirst()
                .orElseThrow());
        assertThat(chequeQuery).containsEntry("status", "ISSUED")
                .containsEntry("clearingFrom", "2026-10-15").containsEntry("clearingTo", "2027-01-06");
        assertThat(sources.lastAuthorization()).isEqualTo("Bearer " + USER_TOKEN);
    }

    // OTHER and UNCLASSIFIED credits are never sales, even when they are the only credits.
    @Test
    void shouldNotCountOtherOrUnclassifiedCreditsAsSales() throws Exception {
        sources.transactions(companyId, List.of(
                transaction(accountId, LocalDate.of(2026, 10, 12), "CREDIT", "70000.00", "OTHER"),
                transaction(accountId, LocalDate.of(2026, 10, 13), "CREDIT", "1234.50", "UNCLASSIFIED"),
                transaction(accountId, LocalDate.of(2026, 10, 13), "DEBIT", "8000.00", "CHEQUE_PAYMENT")));

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.inflowEstimateStatus").value("ESTIMATED"))
                .andExpect(jsonPath("$.totals.expectedSalesInflows").value(0.0))
                .andExpect(jsonPath("$.days[*].closingBalance").value(everyItem(is(500000.0))))
                .andExpect(jsonPath("$.run.dataQuality.unclassifiedCreditCount").value(1))
                .andExpect(jsonPath("$.run.dataQuality.unclassifiedCreditAmount").value(1234.5))
                .andExpect(jsonPath("$.run.warnings[*].code").value(contains("INCOMPLETE_CLASSIFICATION")));
    }

    @Test
    void shouldFlagShortagesFromANegativeConfirmedBalance() throws Exception {
        sources.balance(companyId, accountId, "-2500.75", BALANCE_AS_OF);
        sources.transactions(companyId, List.of());

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.openingBalance").value(-2500.75))
                .andExpect(jsonPath("$.days[0].cashShortage").value(true))
                .andExpect(jsonPath("$.totals.firstShortageDate").value("2026-10-15"))
                .andExpect(jsonPath("$.totals.shortageDays").value(84));
    }

    @Test
    void shouldFlagTheFirstDayAChequeTakesTheBalanceBelowZero() throws Exception {
        sources.balance(companyId, accountId, "100000.00", BALANCE_AS_OF);
        sources.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000555", "800000.00", LocalDate.of(2026, 10, 16), "ISSUED")));

        // 15 Oct: 100000; 16 Oct: -700000; Monday 19 Oct: +100000; Tuesday 20 Oct: +50000.
        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.days[0].cashShortage").value(false))
                .andExpect(jsonPath("$.days[1].closingBalance").value(-700000.0))
                .andExpect(jsonPath("$.days[1].cashShortage").value(true))
                .andExpect(jsonPath("$.totals.firstShortageDate").value("2026-10-16"))
                .andExpect(jsonPath("$.totals.lowestClosingBalance").value(-700000.0));
    }

    @Test
    void shouldRefuseToForecastWithoutAConfirmedBalance() throws Exception {
        sources.reply(FakeSourceServices.balancePath(companyId, accountId), 404,
                "{\"detail\": \"No confirmed balance has been recorded for this bank account.\"}");

        create().andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No confirmed LKR bank balance is available for the company's "
                        + "current account. Import a bank statement first."));

        assertThat(count("SELECT count(*) FROM forecast_runs WHERE company_id = ?", companyId)).isZero();
    }

    @Test
    void shouldRefuseABalanceInAnotherCurrency() throws Exception {
        sources.reply(FakeSourceServices.balancePath(companyId, accountId), 200, """
                {"bankAccountId": "%s", "balanceAmount": 100.00, "currency": "USD", "balanceAsOf": "%s"}
                """.formatted(accountId, BALANCE_AS_OF));

        create().andExpect(status().isConflict());
    }

    @Test
    void shouldRefuseToForecastWithoutAnActiveCurrentAccount() throws Exception {
        sources.reply(FakeSourceServices.accountsPath(companyId), 200, """
                [{"id": "%s", "accountType": "CURRENT", "status": "INACTIVE"}]
                """.formatted(accountId));

        create().andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The company has no active current account."));
    }

    @Test
    void shouldStartFromAStaleBalanceAndLabelIt() throws Exception {
        sources.balance(companyId, accountId, "500000.00", "2026-09-30T18:29:59Z");

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.balanceStale").value(true))
                .andExpect(jsonPath("$.run.dataQuality.balanceAgeDays").value(15))
                .andExpect(jsonPath("$.run.horizonStartDate").value("2026-10-01"))
                .andExpect(jsonPath("$.run.horizonEndDate").value("2026-12-23"))
                .andExpect(jsonPath("$.days[0].openingBalance").value(500000.0))
                .andExpect(jsonPath("$.run.warnings[0].code").value("BALANCE_STALE"))
                .andExpect(jsonPath("$.run.warnings[0].message")
                        .value("The confirmed bank balance is 15 days old. Import a recent bank statement."));
    }

    @Test
    void shouldLeaveInflowsOutWhenHistoryIsInsufficient() throws Exception {
        sources.statements(companyId, accountId, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 10, 14));
        sources.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000123", "250000.00", LocalDate.of(2026, 10, 20), "ISSUED")));

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.inflowEstimateStatus").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.run.dataQuality.statementCoveredDays").value(21))
                .andExpect(jsonPath("$.run.dataQuality.coveredDaysPerWeekday.MONDAY").value(3))
                .andExpect(jsonPath("$.run.warnings[*].code").value(hasItem("INSUFFICIENT_HISTORY")))
                .andExpect(jsonPath("$.days[*].expectedSalesInflows").value(everyItem(nullValue())))
                .andExpect(jsonPath("$.days[*].expectedCashSalesDeposits").value(everyItem(nullValue())))
                .andExpect(jsonPath("$.days[5].closingBalance").value(250000.0))
                .andExpect(jsonPath("$.totals.expectedSalesInflows").value(nullValue()))
                .andExpect(jsonPath("$.totals.closingBalance").value(250000.0));
    }

    @Test
    void shouldReturnTheExistingRunWhenNothingChanged() throws Exception {
        String first = create().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = create().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.run.id")).isEqualTo(JsonPath.read(first, "$.run.id"));
        assertThat(count("SELECT count(*) FROM forecast_runs WHERE company_id = ?", companyId)).isEqualTo(1);

        // Any change to the inputs, here a new cheque, makes a new run.
        sources.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000900", "1000.00", LocalDate.of(2026, 11, 2), "ISSUED")));
        create().andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM forecast_runs WHERE company_id = ?", companyId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM forecast_model_configs WHERE company_id = ?", companyId)).isEqualTo(1);
    }

    // A saved run keeps the cheques it used, even after they change in payment-service.
    @Test
    void shouldKeepASavedRunUnchangedWhenSourcesChange() throws Exception {
        UUID chequeId = UUID.randomUUID();
        sources.cheques(companyId, List.of(
                cheque(chequeId, accountId, "000123", "250000.00", LocalDate.of(2026, 10, 20), "ISSUED")));
        String runId = JsonPath.read(create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");

        sources.cheques(companyId, List.of(
                cheque(chequeId, accountId, "000123", "250000.00", LocalDate.of(2026, 10, 20), "CANCELLED")));
        sources.balance(companyId, accountId, "1.00", BALANCE_AS_OF);

        mockMvc.perform(get(runsUrl(companyId) + "/" + runId).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.openingBalance").value(500000.0))
                .andExpect(jsonPath("$.cheques[0].chequeId").value(chequeId.toString()))
                .andExpect(jsonPath("$.days[5].expectedChequeOutflows").value(250000.0));
    }

    @Test
    void shouldListAndReadRunsOnlyWithinTheCompany() throws Exception {
        String runId = JsonPath.read(create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");
        UUID otherCompany = UUID.randomUUID();
        identityService.grant(otherCompany, "FINANCE_CONTROLLER");

        mockMvc.perform(get(runsUrl(companyId)).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(runId))
                .andExpect(jsonPath("$[0].days").doesNotExist());
        mockMvc.perform(get(runsUrl(otherCompany)).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
        mockMvc.perform(get(runsUrl(otherCompany) + "/" + runId).with(user()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(runsUrl(otherCompany) + "/" + runId + "/forecast-vs-actual").with(user()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(runsUrl(companyId) + "/" + UUID.randomUUID()).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Forecast run not found."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accounts", "imports", "transactions", "cheques"})
    void shouldFailClosedWhenASourceServiceFails(String failing) throws Exception {
        String path = switch (failing) {
            case "accounts" -> FakeSourceServices.accountsPath(companyId);
            case "imports" -> FakeSourceServices.importsPath(companyId);
            case "transactions" -> FakeSourceServices.transactionsPath(companyId);
            default -> FakeSourceServices.chequesPath(companyId);
        };
        sources.reply(path, 500, "{}");

        create().andExpect(status().isServiceUnavailable());

        assertThat(count("SELECT count(*) FROM forecast_runs WHERE company_id = ?", companyId)).isZero();
    }

    @Test
    void shouldFailClosedOnInvalidSourceData() throws Exception {
        sources.reply(FakeSourceServices.transactionsPath(companyId), 200, """
                [{"id": "%s", "bankAccountId": "%s", "transactionDate": "2026-10-12", "direction": "CREDIT",
                  "category": "CASH_SALES_DEPOSIT"}]
                """.formatted(UUID.randomUUID(), accountId));

        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Bank data could not be loaded. Please try again later."));
    }

    @Test
    void shouldFailClosedWhenPaymentServiceTimesOut() throws Exception {
        // Answers after the 1s read timeout configured for tests.
        sources.replyAfterDelay(FakeSourceServices.chequesPath(companyId), "[]", Duration.ofSeconds(3));

        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Cheque data could not be loaded. Please try again later."));

        assertThat(count("SELECT count(*) FROM forecast_runs WHERE company_id = ?", companyId)).isZero();
    }

    // A Monday cash deposit and a Tuesday card settlement every week of the history, plus
    // transactions that must not count: other categories, other accounts and older dates.
    private List<String> salesHistory() {
        List<String> transactions = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 8, 20); !date.isAfter(LocalDate.of(2026, 10, 14));
             date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                transactions.add(transaction(accountId, date, "CREDIT", "100000.00", "CASH_SALES_DEPOSIT"));
            }
            if (date.getDayOfWeek() == DayOfWeek.TUESDAY) {
                transactions.add(transaction(accountId, date, "CREDIT", "50000.00", "CARD_SALES_SETTLEMENT"));
            }
        }
        transactions.add(transaction(accountId, LocalDate.of(2026, 9, 16), "CREDIT", "999999.00", "OTHER"));
        transactions.add(transaction(accountId, LocalDate.of(2026, 9, 17), "CREDIT", "77777.77", "UNCLASSIFIED"));
        transactions.add(transaction(accountId, LocalDate.of(2026, 9, 18), "DEBIT", "120000.00", "CHEQUE_PAYMENT"));
        transactions.add(transaction(UUID.randomUUID(), LocalDate.of(2026, 10, 12), "CREDIT", "5000000.00",
                "CASH_SALES_DEPOSIT"));
        transactions.add(transaction(accountId, LocalDate.of(2026, 8, 10), "CREDIT", "5000000.00",
                "CASH_SALES_DEPOSIT"));
        return transactions;
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post(runsUrl(companyId)).with(user()));
    }

    private RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.tokenValue(USER_TOKEN).subject(userId.toString()));
    }

    private static String runsUrl(UUID company) {
        return "/api/v1/forecast/companies/" + company + "/forecast-runs";
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
