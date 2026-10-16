package com.flowledger.forecast.controller;

import com.flowledger.forecast.support.FakeIdentityServer;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.flowledger.forecast.support.FakeSourceServices.approvedSale;
import static com.flowledger.forecast.support.FakeSourceServices.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Option B end to end: a company without a configuration gets PREVIOUS_YEAR_PATTERN; inputs come
 * from fake bank-integration and cash-management services over HTTP, cheques from a verified
 * projection. "Today" is 15 October 2026 and the balance is from 14 October, so the horizon is
 * 15 October 2026 to 6 January 2027, the growth window 20 August to 14 October 2026, and the
 * previous-year days 21 August 2025 to 7 January 2026. Needs the local forecast database; each
 * test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
@Transactional
class ForecastPreviousYearIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeSourceServices sources = FakeSourceServices.instance();
    private static final String USER_TOKEN = "user-access-token";
    private static final LocalDate WINDOW_FROM = LocalDate.of(2026, 8, 20);
    private static final LocalDate BALANCE_DATE = LocalDate.of(2026, 10, 14);
    private static final UUID BATCH = UUID.randomUUID();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
        registry.add("flowledger.bank-integration.base-url", sources::baseUrl);
        registry.add("flowledger.cash-management.base-url", sources::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID accountId;
    private final List<String> transactions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        transactions.clear();
        identityService.reset();
        sources.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        sources.currentAccount(companyId, accountId);
        sources.balance(companyId, accountId, "500000.00", "2026-10-14T18:29:59Z");
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), BALANCE_DATE);
        currentYear("1100.00", "2200.00");
        sources.transactions(companyId, transactions);
        // Last year comes from an approved import: sales a day before each receipt day (lag 1).
        sources.salesHistory(companyId, sales(date -> new String[]{"1000.00", "2000.00"}));
        new ProjectedCheques(jdbcTemplate).cheques(companyId, List.of());
    }

    @Test
    void shouldForecastFromLastYearsPatternAndThisYearsCollections() throws Exception {
        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.modelVersion").value("2:PREVIOUS_YEAR_PATTERN"))
                .andExpect(jsonPath("$.run.config.strategy").value("PREVIOUS_YEAR_PATTERN"))
                .andExpect(jsonPath("$.run.config.methodCode").value("PREVIOUS_YEAR_CORRESPONDING_WEEK_PATTERN"))
                .andExpect(jsonPath("$.run.config.referenceOffsetDays").value(364))
                .andExpect(jsonPath("$.run.config.cashReceiptLagDays").value(1))
                .andExpect(jsonPath("$.run.config.growthRatioWarningHigh").value("2.0"))
                .andExpect(jsonPath("$.run.inflowEstimateStatus").value("ESTIMATED"))
                .andExpect(jsonPath("$.run.dataQuality.pattern.cashGrowthRatio").value(1.1))
                .andExpect(jsonPath("$.run.dataQuality.pattern.cardGrowthRatio").value(1.1))
                .andExpect(jsonPath("$.run.dataQuality.pattern.comparableCashDays").value(56))
                .andExpect(jsonPath("$.run.dataQuality.pattern.referenceFromImports").value(2 * 56 + 2 * 84))
                .andExpect(jsonPath("$.run.warnings[*].code", hasItem("PATTERN_FROM_SALES_HISTORY")))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-15"))
                .andExpect(jsonPath("$.days[0].expectedCashSalesDeposits").value(1100.0))
                .andExpect(jsonPath("$.days[0].expectedCardSalesSettlements").value(2200.0))
                .andExpect(jsonPath("$.days[83].date").value("2027-01-06"))
                .andExpect(jsonPath("$.totals.expectedSalesInflows").value(84 * 3300.0));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, is_active, method_code, parameters ->> 'strategy' AS strategy
                FROM forecast_model_configs WHERE company_id = ?
                """, companyId)).containsEntry("version", 1).containsEntry("is_active", true)
                .containsEntry("strategy", "PREVIOUS_YEAR_PATTERN");
    }

    @Test
    void shouldReadExactlyTheNeededHistoryWithTheUsersToken() throws Exception {
        create().andExpect(status().isCreated());

        List<String> requests = sources.requests();
        assertThat(requests.stream().filter(r -> r.startsWith(FakeSourceServices.salesHistoryPath(companyId)))
                .map(FakeSourceServices::params).toList())
                .containsExactly(Map.of("from", "2025-08-20", "to", "2026-01-06"));
        assertThat(requests.stream().filter(r -> r.startsWith(FakeSourceServices.transactionsPath(companyId)))
                .map(FakeSourceServices::params).map(p -> p.get("from") + "/" + p.get("to")).toList())
                .containsExactly("2026-08-20/2026-10-14", "2025-08-21/2026-01-07");
        assertThat(sources.lastAuthorization()).isEqualTo("Bearer " + USER_TOKEN);
        // Daily sales entered by hand are never read.
        assertThat(requests).noneMatch(r -> r.contains("/sales?") || r.endsWith("/sales"));
    }

    @Test
    void shouldUseBankStatementsInsteadOfImportsWhereBothExistWithoutAddingThem() throws Exception {
        sources.statements(companyId, accountId, LocalDate.of(2025, 10, 16), LocalDate.of(2025, 10, 22),
                LocalDate.of(2026, 8, 1), BALANCE_DATE);
        for (LocalDate day = LocalDate.of(2025, 10, 16); !day.isAfter(LocalDate.of(2025, 10, 22)); day = day.plusDays(1)) {
            transactions.add(transaction(accountId, day, "CREDIT", "500.00", "CASH_SALES_DEPOSIT"));
            transactions.add(transaction(accountId, day, "CREDIT", "600.00", "CARD_SALES_SETTLEMENT"));
            // Never sales, whatever their amount.
            transactions.add(transaction(accountId, day, "CREDIT", "99999.00", "OTHER"));
        }
        sources.transactions(companyId, transactions);

        create().andExpect(status().isCreated())
                // 15-21 October 2026 refer to the statement-covered 16-22 October 2025: 500 x 1.1, not (500 + 1000) x 1.1.
                .andExpect(jsonPath("$.days[0].expectedCashSalesDeposits").value(550.0))
                .andExpect(jsonPath("$.days[0].expectedCardSalesSettlements").value(660.0))
                .andExpect(jsonPath("$.days[6].expectedCashSalesDeposits").value(550.0))
                .andExpect(jsonPath("$.days[7].expectedCashSalesDeposits").value(1100.0))
                .andExpect(jsonPath("$.run.dataQuality.pattern.referenceFromBank").value(14));
    }

    @Test
    void shouldForecastNothingOnAnApprovedZeroDay() throws Exception {
        // 18 October 2026 refers to 19 October 2025, received from sales of 18 October 2025.
        sources.salesHistory(companyId, sales(date -> date.equals(LocalDate.of(2025, 10, 18))
                ? new String[]{"0.00", "0.00"} : new String[]{"1000.00", "2000.00"}));

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.inflowEstimateStatus").value("ESTIMATED"))
                .andExpect(jsonPath("$.days[3].date").value("2026-10-18"))
                .andExpect(jsonPath("$.days[3].expectedSalesInflows").value(0.0))
                .andExpect(jsonPath("$.days[4].expectedSalesInflows").value(3300.0));
    }

    @Test
    void shouldNotEstimateInflowsWhenAReferenceDayIsMissing() throws Exception {
        sources.salesHistory(companyId, sales(date -> date.equals(LocalDate.of(2025, 11, 1))
                ? null : new String[]{"1000.00", "2000.00"}));

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.inflowEstimateStatus").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.days[*].expectedSalesInflows", everyItem(nullValue())))
                .andExpect(jsonPath("$.run.dataQuality.pattern.insufficientReason").value("MISSING_REFERENCE_DAYS"))
                .andExpect(jsonPath("$.run.warnings[0].code").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.run.warnings[0].message", containsString("1 day(s) of last year")));
    }

    @Test
    void shouldWarnAboutExtremeGrowthWithoutCappingIt() throws Exception {
        transactions.clear();
        currentYear("3000.00", "2200.00");
        sources.transactions(companyId, transactions);

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.days[0].expectedCashSalesDeposits").value(3000.0))
                .andExpect(jsonPath("$.run.dataQuality.pattern.cashRatioOutOfRange").value(true))
                .andExpect(jsonPath("$.run.dataQuality.pattern.cardRatioOutOfRange").value(false))
                .andExpect(jsonPath("$.run.warnings[*].code", hasItem("GROWTH_RATIO_OUT_OF_RANGE")));
    }

    @Test
    void shouldFailClosedWhenSalesHistoryCannotBeRead() throws Exception {
        sources.reply(FakeSourceServices.salesHistoryPath(companyId), 500, "{}");
        refused();

        sources.reply(FakeSourceServices.salesHistoryPath(companyId), 200, "{\"days\": ["
                + approvedSale(LocalDate.of(2025, 9, 1), "1.00", "1.00", BATCH) + ","
                + approvedSale(LocalDate.of(2025, 9, 1), "2.00", "2.00", BATCH) + "]}");
        refused();

        sources.reply(FakeSourceServices.salesHistoryPath(companyId), 200, "{\"days\": ["
                + approvedSale(LocalDate.of(2025, 9, 1), "-1.00", "1.00", BATCH) + "]}");
        refused();

        sources.reply(FakeSourceServices.salesHistoryPath(companyId), 200, "{\"days\": ["
                + approvedSale(LocalDate.of(2024, 1, 1), "1.00", "1.00", BATCH) + "]}");
        refused();

        sources.reply(FakeSourceServices.salesHistoryPath(companyId), 200, "{}");
        refused();

        // Answers after the 1s read timeout configured for tests.
        sources.replyAfterDelay(FakeSourceServices.salesHistoryPath(companyId), "{\"days\": []}", Duration.ofSeconds(3));
        refused();
    }

    @Test
    void shouldSaveANewRunOnlyWhenTheInputsChange() throws Exception {
        String first = JsonPath.read(create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");
        String again = JsonPath.read(create().andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.run.id");
        assertThat(again).isEqualTo(first);

        // One approved historical value changes: a different digest and a new run.
        sources.salesHistory(companyId, sales(date -> date.equals(LocalDate.of(2025, 12, 1))
                ? new String[]{"1000.01", "2000.00"} : new String[]{"1000.00", "2000.00"}));
        String changed = JsonPath.read(create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");
        assertThat(changed).isNotEqualTo(first);

        // The same values from another approved batch are a different input as well.
        UUID otherBatch = UUID.randomUUID();
        List<String> rebatched = new ArrayList<>();
        for (LocalDate sale = LocalDate.of(2025, 8, 20); !sale.isAfter(LocalDate.of(2026, 1, 6)); sale = sale.plusDays(1)) {
            rebatched.add(approvedSale(sale, sale.equals(LocalDate.of(2025, 12, 1)) ? "1000.01" : "1000.00", "2000.00",
                    otherBatch));
        }
        sources.salesHistory(companyId, rebatched);
        create().andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForList("SELECT input_snapshot_hash FROM forecast_runs WHERE company_id = ?",
                String.class, companyId)).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void shouldCompareWithBankActualsOnlyAndNeverReadSalesForIt() throws Exception {
        String runId = JsonPath.read(create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");
        sources.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 15));
        sources.transactions(companyId, List.of(
                transaction(accountId, LocalDate.of(2026, 10, 15), "CREDIT", "700.00", "CASH_SALES_DEPOSIT")));

        mockMvc.perform(get(runsUrl() + "/" + runId + "/forecast-vs-actual").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].forecastSalesInflows").value(3300.0))
                .andExpect(jsonPath("$.days[0].actualSalesInflows").value(700.0));
        assertThat(sources.requests()).noneMatch(r -> r.startsWith(FakeSourceServices.salesHistoryPath(companyId)));
    }

    @Test
    void shouldKeepCashiersOutAndForwardOnlyTheirOwnCompany() throws Exception {
        identityService.grant(companyId, "CASHIER");
        create().andExpect(status().isForbidden());
        assertThat(sources.requests()).noneMatch(r -> r.startsWith(FakeSourceServices.salesHistoryPath(companyId)));
    }

    // Helpers

    private void refused() throws Exception {
        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Approved sales history could not be loaded. Please try again later."));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM forecast_runs WHERE company_id = ?",
                Integer.class, companyId)).isZero();
    }

    private void currentYear(String cash, String card) {
        for (LocalDate day = WINDOW_FROM; !day.isAfter(BALANCE_DATE); day = day.plusDays(1)) {
            transactions.add(transaction(accountId, day, "CREDIT", cash, "CASH_SALES_DEPOSIT"));
            transactions.add(transaction(accountId, day, "CREDIT", card, "CARD_SALES_SETTLEMENT"));
        }
    }

    // Approved sales for every sale date whose receipt (one day later) is a previous-year day; null leaves it out.
    private static List<String> sales(Function<LocalDate, String[]> amounts) {
        List<String> days = new ArrayList<>();
        for (LocalDate sale = LocalDate.of(2025, 8, 20); !sale.isAfter(LocalDate.of(2026, 1, 6)); sale = sale.plusDays(1)) {
            String[] values = amounts.apply(sale);
            if (values != null) {
                days.add(approvedSale(sale, values[0], values[1], BATCH));
            }
        }
        return days;
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post(runsUrl()).with(user()));
    }

    private RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.tokenValue(USER_TOKEN).subject(UUID.randomUUID().toString()));
    }

    private String runsUrl() {
        return "/api/v1/forecast/companies/" + companyId + "/forecast-runs";
    }

}
