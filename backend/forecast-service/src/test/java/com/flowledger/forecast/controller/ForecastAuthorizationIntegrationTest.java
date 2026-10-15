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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may create and read forecasts. The forecast is financial business data: only company
 * members with a finance-page role, never a system admin by platform role alone. Needs the
 * local forecast PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
@Transactional
class ForecastAuthorizationIntegrationTest {

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

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        identityService.reset();
        sources.reset();
        sources.currentAccount(companyId, accountId);
        sources.balance(companyId, accountId, "1000.00", "2026-10-14T18:29:59Z");
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14));
        sources.transactions(companyId, List.of());
        sources.cheques(companyId, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"})
    void shouldLetFinancePageRolesCreateAndReadForecasts(String role) throws Exception {
        identityService.grant(companyId, role);

        String runId = JsonPath.read(mockMvc.perform(post(runsUrl()).with(user()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.run.id");

        mockMvc.perform(get(runsUrl()).with(user())).andExpect(status().isOk());
        mockMvc.perform(get(runsUrl() + "/" + runId).with(user())).andExpect(status().isOk());
        mockMvc.perform(get(runsUrl() + "/" + runId + "/forecast-vs-actual").with(user())).andExpect(status().isOk());
    }

    @Test
    void shouldDenyCashierWithoutReadingAnySource() throws Exception {
        identityService.grant(companyId, "CASHIER");

        assertDeniedEverywhere();
    }

    // Without a company membership a system admin has administration access only (section 4).
    @Test
    void shouldDenySystemAdminWithoutMembership() throws Exception {
        identityService.grantPlatformAdmin(companyId);

        assertDeniedEverywhere();
    }

    @Test
    void shouldDenyNonMembers() throws Exception {
        mockMvc.perform(post(runsUrl()).with(user()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You do not have access to this company."));
    }

    @Test
    void shouldRequireAToken() throws Exception {
        mockMvc.perform(post(runsUrl())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(runsUrl())).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldFailClosedWhenIdentityServiceIsUnavailable() throws Exception {
        identityService.reply(companyId, 500, "{}");

        mockMvc.perform(post(runsUrl()).with(user()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Company access could not be verified. Please try again later."));
        mockMvc.perform(get(runsUrl()).with(user())).andExpect(status().isServiceUnavailable());

        assertThat(sources.requests()).isEmpty();
        assertThat(countRuns()).isZero();
    }

    private void assertDeniedEverywhere() throws Exception {
        UUID runId = UUID.randomUUID();
        mockMvc.perform(post(runsUrl()).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(runsUrl()).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(runsUrl() + "/" + runId).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(runsUrl() + "/" + runId + "/forecast-vs-actual").with(user()))
                .andExpect(status().isForbidden());

        assertThat(sources.requests()).isEmpty();
        assertThat(countRuns()).isZero();
    }

    private String runsUrl() {
        return "/api/v1/forecast/companies/" + companyId + "/forecast-runs";
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    private int countRuns() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM forecast_runs WHERE company_id = ?", Integer.class, companyId);
    }
}
