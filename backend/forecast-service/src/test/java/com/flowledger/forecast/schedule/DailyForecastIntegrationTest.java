package com.flowledger.forecast.schedule;

import com.flowledger.forecast.support.FakeIdentityServer;
import com.flowledger.forecast.support.FakeSourceServices;
import com.flowledger.forecast.support.FixedClockConfig;
import com.flowledger.forecast.support.ForecastConfigs;
import com.flowledger.forecast.support.ProjectedCheques;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The daily scheduled forecast: inputs through the other services' internal APIs with the internal
 * keys (FakeSourceServices refuses a wrong key), runs saved as SCHEDULED with no user. "Today" is
 * 15 October 2026. Needs the local forecast database; each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
@Transactional
class DailyForecastIntegrationTest {

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

    @Autowired
    private DailyForecastService dailyForecast;

    private UUID companyId;
    private UUID accountId;
    private ProjectedCheques projected;

    @BeforeEach
    void setUp() {
        projected = new ProjectedCheques(jdbcTemplate);
        companyId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        ForecastConfigs.weekdayAverage(jdbcTemplate, companyId);
        identityService.reset();
        sources.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        sources.currentAccount(companyId, accountId);
        sources.balance(companyId, accountId, "500000.00", "2026-10-14T18:29:59Z");
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14));
        sources.transactions(companyId, weeklySales());
        projected.cheques(companyId, List.of());
    }

    @Test
    void shouldMakeAScheduledRunThroughTheInternalApisWhenTheInputsChanged() throws Exception {
        userRun();
        // A cheque recorded after the user's forecast: today's scheduled run includes it.
        projected.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000123", "250000.00", LocalDate.of(2026, 10, 20), "ISSUED")));
        int requestsBefore = sources.requests().size();

        DailyForecastService.Outcome outcome = dailyForecast.run(List.of(companyId));

        assertThat(outcome).isEqualTo(new DailyForecastService.Outcome(1, 1, 0, 0));
        Map<String, Object> scheduled = jdbcTemplate.queryForMap("""
                SELECT trigger_type, triggered_by, horizon_start_date FROM forecast_runs
                WHERE company_id = ? ORDER BY created_at DESC, trigger_type DESC LIMIT 1
                """, companyId);
        assertThat(scheduled).containsEntry("trigger_type", "SCHEDULED").containsEntry("triggered_by", null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(expected_cheque_outflows_amount) FROM forecast_daily_results d
                JOIN forecast_runs r ON r.id = d.forecast_run_id
                WHERE r.company_id = ? AND r.trigger_type = 'SCHEDULED'
                """, java.math.BigDecimal.class, companyId)).isEqualByComparingTo("250000.00");
        // Every input was read from the internal API, never the public one.
        assertThat(sources.requests().subList(requestsBefore, sources.requests().size()))
                .isNotEmpty().allMatch(FakeSourceServices::isInternal);
    }

    @Test
    void shouldNotSaveACopyWhenNothingChangedToday() throws Exception {
        userRun();

        assertThat(dailyForecast.run(List.of(companyId))).isEqualTo(new DailyForecastService.Outcome(1, 0, 1, 0));
        assertThat(runs("SCHEDULED")).isZero();
    }

    @Test
    void shouldSkipACompanyThatCannotBeForecastAndContinue() throws Exception {
        UUID noBalance = UUID.randomUUID();
        UUID noAccountSetUp = UUID.randomUUID();
        ForecastConfigs.weekdayAverage(jdbcTemplate, noBalance);
        sources.currentAccount(noBalance, UUID.randomUUID());
        sources.statements(noBalance, accountId);

        DailyForecastService.Outcome outcome = dailyForecast.run(List.of(noBalance, noAccountSetUp, companyId));

        assertThat(outcome).isEqualTo(new DailyForecastService.Outcome(3, 1, 0, 2));
        assertThat(runs("SCHEDULED")).isEqualTo(1);
    }

    @Test
    void shouldChooseCompaniesWhoseUsersMadeAForecastRecently() throws Exception {
        UUID idle = UUID.randomUUID();
        assertThat(dailyForecast.companiesInUse()).doesNotContain(companyId, idle);

        userRun();

        assertThat(dailyForecast.companiesInUse()).contains(companyId).doesNotContain(idle);
    }

    private void userRun() throws Exception {
        mockMvc.perform(post("/api/v1/forecast/companies/" + companyId + "/forecast-runs")
                        .with(jwt().jwt(jwt -> jwt.tokenValue("user-token").subject(UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());
    }

    private int runs(String trigger) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM forecast_runs WHERE company_id = ? AND trigger_type = ?",
                Integer.class, companyId, trigger);
    }

    // A Monday cash deposit of 100000 and a Tuesday card settlement of 50000 every week.
    private List<String> weeklySales() {
        List<String> transactions = new ArrayList<>();
        for (LocalDate date = LocalDate.of(2026, 8, 1); !date.isAfter(LocalDate.of(2026, 10, 14)); date = date.plusDays(1)) {
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
