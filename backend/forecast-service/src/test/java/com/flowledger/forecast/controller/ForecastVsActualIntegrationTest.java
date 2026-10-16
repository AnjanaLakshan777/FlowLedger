package com.flowledger.forecast.controller;

import com.flowledger.forecast.support.FakeIdentityServer;
import com.flowledger.forecast.support.ForecastConfigs;
import com.flowledger.forecast.support.FakeSourceServices;
import com.flowledger.forecast.support.FixedClockConfig;
import com.flowledger.forecast.support.ProjectedCheques;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.flowledger.forecast.support.FakeSourceServices.transaction;
import static com.flowledger.forecast.support.ProjectedCheques.cheque;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A forecast made from the balance of 30 September 2026 compared with October's actual bank
 * transactions. "Today" is 15 October 2026. Needs the local forecast PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
@Transactional
class ForecastVsActualIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeSourceServices sources = FakeSourceServices.instance();

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

    private UUID companyId;
    private UUID accountId;
    private ProjectedCheques projected;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        projected = new ProjectedCheques(jdbcTemplate);
        // These tests cover the WEEKDAY_AVERAGE method; PREVIOUS_YEAR_PATTERN has its own tests.
        ForecastConfigs.weekdayAverage(jdbcTemplate, companyId);
        accountId = UUID.randomUUID();
        identityService.reset();
        sources.reset();
        identityService.grant(companyId, "ACCOUNTANT");
        sources.currentAccount(companyId, accountId);
        // End of 30 September in Colombo; the forecast runs from 1 October to 23 December.
        sources.balance(companyId, accountId, "500000.00", "2026-09-30T18:29:59Z");
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14));
        sources.transactions(companyId, weeklySales(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 9, 30)));
        projected.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000123", "200000.00", LocalDate.of(2026, 10, 7), "ISSUED"),
                cheque(UUID.randomUUID(), accountId, "000124", "30000.00", LocalDate.of(2026, 10, 20), "ISSUED")));
    }

    @Test
    void shouldCompareForecastWithClassifiedActualsByTransactionDate() throws Exception {
        String runId = createRun();
        sources.transactions(companyId, List.of(
                transaction(accountId, LocalDate.of(2026, 10, 5), "CREDIT", "120000.00", "CASH_SALES_DEPOSIT"),
                transaction(accountId, LocalDate.of(2026, 10, 6), "CREDIT", "45000.00", "CARD_SALES_SETTLEMENT"),
                transaction(accountId, LocalDate.of(2026, 10, 7), "DEBIT", "200000.00", "CHEQUE_PAYMENT"),
                transaction(accountId, LocalDate.of(2026, 10, 8), "CREDIT", "5000.00", "OTHER"),
                transaction(accountId, LocalDate.of(2026, 10, 9), "DEBIT", "1000.00", "UNCLASSIFIED"),
                transaction(accountId, LocalDate.of(2026, 10, 12), "CREDIT", "90000.00", "CASH_SALES_DEPOSIT"),
                // Outside the compared days, and another account: never counted.
                transaction(accountId, LocalDate.of(2026, 9, 28), "CREDIT", "999.00", "CASH_SALES_DEPOSIT"),
                transaction(UUID.randomUUID(), LocalDate.of(2026, 10, 5), "CREDIT", "777.00", "CASH_SALES_DEPOSIT")));

        compare(runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forecastRunId").value(runId))
                .andExpect(jsonPath("$.horizonStartDate").value("2026-10-01"))
                .andExpect(jsonPath("$.horizonEndDate").value("2026-12-23"))
                .andExpect(jsonPath("$.comparedThrough").value("2026-10-14"))
                .andExpect(jsonPath("$.days", hasSize(84)))
                // Monday 5 October: forecast 100000 cash, actually 120000.
                .andExpect(jsonPath("$.days[4].date").value("2026-10-05"))
                .andExpect(jsonPath("$.days[4].actualsAvailable").value(true))
                .andExpect(jsonPath("$.days[4].forecastSalesInflows").value(100000.0))
                .andExpect(jsonPath("$.days[4].actualSalesInflows").value(120000.0))
                .andExpect(jsonPath("$.days[4].salesInflowDifference").value(20000.0))
                .andExpect(jsonPath("$.days[5].actualSalesInflows").value(45000.0))
                .andExpect(jsonPath("$.days[5].salesInflowDifference").value(-5000.0))
                // Wednesday 7 October: the cheque cleared as expected.
                .andExpect(jsonPath("$.days[6].forecastChequeOutflows").value(200000.0))
                .andExpect(jsonPath("$.days[6].actualChequeOutflows").value(200000.0))
                .andExpect(jsonPath("$.days[6].chequeOutflowDifference").value(0.0))
                // OTHER and UNCLASSIFIED move the balance but count as neither sales nor cheques.
                .andExpect(jsonPath("$.days[7].actualSalesInflows").value(0.0))
                .andExpect(jsonPath("$.days[8].actualChequeOutflows").value(0.0))
                .andExpect(jsonPath("$.days[8].unclassifiedTransactions").value(1))
                .andExpect(jsonPath("$.days[3].actualClosingBalance").value(500000.0))
                .andExpect(jsonPath("$.days[7].actualClosingBalance").value(470000.0))
                .andExpect(jsonPath("$.days[13].date").value("2026-10-14"))
                .andExpect(jsonPath("$.days[13].forecastClosingBalance").value(600000.0))
                .andExpect(jsonPath("$.days[13].actualClosingBalance").value(559000.0))
                .andExpect(jsonPath("$.days[13].closingBalanceDifference").value(-41000.0))
                // Today and later: not in any statement yet, so unknown rather than zero.
                .andExpect(jsonPath("$.days[14].date").value("2026-10-15"))
                .andExpect(jsonPath("$.days[14].actualsAvailable").value(false))
                .andExpect(jsonPath("$.days[14].actualSalesInflows").value(nullValue()))
                .andExpect(jsonPath("$.days[14].actualClosingBalance").value(nullValue()))
                .andExpect(jsonPath("$.days[19].forecastChequeOutflows").value(30000.0))
                .andExpect(jsonPath("$.days[19].actualChequeOutflows").value(nullValue()))
                .andExpect(jsonPath("$.totals.daysCompared").value(14))
                .andExpect(jsonPath("$.totals.forecastSalesInflows").value(300000.0))
                .andExpect(jsonPath("$.totals.actualSalesInflows").value(255000.0))
                .andExpect(jsonPath("$.totals.salesInflowDifference").value(-45000.0))
                .andExpect(jsonPath("$.totals.forecastChequeOutflows").value(200000.0))
                .andExpect(jsonPath("$.totals.actualChequeOutflows").value(200000.0))
                .andExpect(jsonPath("$.totals.unclassifiedTransactionCount").value(1))
                .andExpect(jsonPath("$.totals.unclassifiedTransactionAmount").value(1000.0))
                .andExpect(jsonPath("$.warnings[*].code").value(contains("INCOMPLETE_CLASSIFICATION")));

        Map<String, String> query = FakeSourceServices.params(sources.requests().stream()
                .filter(request -> request.startsWith(FakeSourceServices.transactionsPath(companyId)))
                .reduce((first, last) -> last).orElseThrow());
        assertThat(query).containsEntry("from", "2026-10-01").containsEntry("to", "2026-10-15")
                .containsEntry("bankAccountId", accountId.toString());
    }

    // A day without an imported statement has no actuals, and the actual balance cannot be
    // carried past it.
    @Test
    void shouldNotTreatDaysWithoutStatementsAsZero() throws Exception {
        String runId = createRun();
        sources.statements(companyId, accountId,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 10),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14));
        sources.transactions(companyId, List.of(
                transaction(accountId, LocalDate.of(2026, 10, 12), "CREDIT", "90000.00", "CASH_SALES_DEPOSIT")));

        compare(runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[9].date").value("2026-10-10"))
                .andExpect(jsonPath("$.days[9].actualClosingBalance").value(500000.0))
                .andExpect(jsonPath("$.days[10].date").value("2026-10-11"))
                .andExpect(jsonPath("$.days[10].actualsAvailable").value(false))
                .andExpect(jsonPath("$.days[10].actualSalesInflows").value(nullValue()))
                .andExpect(jsonPath("$.days[11].actualsAvailable").value(true))
                .andExpect(jsonPath("$.days[11].actualSalesInflows").value(90000.0))
                .andExpect(jsonPath("$.days[11].actualClosingBalance").value(nullValue()))
                .andExpect(jsonPath("$.totals.daysCompared").value(13))
                .andExpect(jsonPath("$.warnings[0].code").value("ACTUALS_INCOMPLETE"))
                .andExpect(jsonPath("$.warnings[0].message").value(
                        "1 past day(s) are not covered by an imported bank statement and have no actual values."));
    }

    @Test
    void shouldNotCompareInflowsThatWereNeverEstimated() throws Exception {
        sources.statements(companyId, accountId, LocalDate.of(2026, 9, 17), LocalDate.of(2026, 10, 14));
        String runId = createRun();
        sources.transactions(companyId, List.of(
                transaction(accountId, LocalDate.of(2026, 10, 5), "CREDIT", "120000.00", "CASH_SALES_DEPOSIT")));

        compare(runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inflowEstimateStatus").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.days[4].forecastSalesInflows").value(nullValue()))
                .andExpect(jsonPath("$.days[4].actualSalesInflows").value(120000.0))
                .andExpect(jsonPath("$.days[4].salesInflowDifference").value(nullValue()))
                .andExpect(jsonPath("$.totals.forecastSalesInflows").value(nullValue()))
                .andExpect(jsonPath("$.totals.salesInflowDifference").value(nullValue()))
                .andExpect(jsonPath("$.totals.actualSalesInflows").value(120000.0))
                .andExpect(jsonPath("$.warnings[0].code").value("INSUFFICIENT_HISTORY"));
    }

    @Test
    void shouldHaveNoActualsBeforeTheHorizonHasPassed() throws Exception {
        sources.balance(companyId, accountId, "500000.00", "2026-10-14T18:29:59Z");
        String runId = createRun();
        int requestsBefore = sources.requests().size();

        compare(runId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparedThrough").value(nullValue()))
                .andExpect(jsonPath("$.days[*].actualsAvailable").value(everyItem(is(false))))
                .andExpect(jsonPath("$.totals.daysCompared").value(0))
                .andExpect(jsonPath("$.warnings", empty()));

        assertThat(sources.requests().size() - requestsBefore).isEqualTo(2);
    }

    @Test
    void shouldFailClosedWhenBankDataIsUnavailable() throws Exception {
        String runId = createRun();
        sources.reply(FakeSourceServices.transactionsPath(companyId), 503, "{}");

        compare(runId).andExpect(status().isServiceUnavailable());
    }

    private String createRun() throws Exception {
        String body = mockMvc.perform(post("/api/v1/forecast/companies/" + companyId + "/forecast-runs").with(user()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.run.id");
    }

    private ResultActions compare(String runId) throws Exception {
        return mockMvc.perform(get("/api/v1/forecast/companies/" + companyId + "/forecast-runs/" + runId
                + "/forecast-vs-actual").with(user()));
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    // A Monday cash deposit of 100000 and a Tuesday card settlement of 50000 every week.
    private List<String> weeklySales(LocalDate from, LocalDate to) {
        List<String> transactions = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                transactions.add(transaction(accountId, date, "CREDIT", "100000.00", "CASH_SALES_DEPOSIT"));
            }
            if (date.getDayOfWeek() == DayOfWeek.TUESDAY) {
                transactions.add(transaction(accountId, date, "CREDIT", "50000.00", "CARD_SALES_SETTLEMENT"));
            }
        }
        return transactions;
    }
}
