package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.config.IdentityServiceProperties;
import com.flowledger.cashmanagement.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role policy and fail-closed behaviour of company authorization for daily sales. Membership
 * answers come from FakeIdentityServer over HTTP. Needs the local cash-management PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DailySaleAuthorizationIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final String USER_TOKEN = "user-access-token";

    @DynamicPropertySource
    static void identityServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdentityServiceProperties identityServiceProperties;

    private UUID companyId;
    private LocalDate yesterday;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        yesterday = LocalDate.now(ZoneId.of("Asia/Colombo")).minusDays(1);
        identityService.reset();
    }

    // Confirmed: a CASHIER sees all of their company's daily sales, not only the ones they recorded.
    @Test
    void shouldLetCashierRecordAndViewAllOfTheirCompanysSales() throws Exception {
        UUID recordedByColleague = insertSale(yesterday.minusDays(1));
        identityService.grant(companyId, "CASHIER");

        String ownSaleId = JsonPath.read(
                create().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        read(recordedByColleague.toString()).andExpect(status().isOk());
        read(ownSaleId).andExpect(status().isOk());
    }

    @Test
    void shouldKeepCashierOutOfAnotherCompanysSales() throws Exception {
        UUID otherCompanyId = UUID.randomUUID();
        identityService.grant(companyId, "CASHIER");
        String otherSalesUrl = "/api/v1/cash-management/companies/" + otherCompanyId + "/sales";

        mockMvc.perform(get(otherSalesUrl).with(userToken())).andExpect(status().isForbidden());
        mockMvc.perform(get(otherSalesUrl + "/" + UUID.randomUUID()).with(userToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(otherSalesUrl).with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"saleDate": "%s", "cashSalesAmount": 100.00, "cardSalesAmount": 0}
                                """.formatted(yesterday)))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE company_id = ?", Integer.class, otherCompanyId)).isZero();
    }

    // Access is checked before the request's date filter, so an outsider learns nothing from it.
    @Test
    void shouldRefuseUserWithoutAccessBeforeValidatingDateFilter() throws Exception {
        mockMvc.perform(get(salesUrl() + "?from=2026-09-05&to=2026-09-01").with(userToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void shouldLetAccountantRecordAndViewSales() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        String saleId = JsonPath.read(
                create().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        read(saleId).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saleId));
        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER"})
    void shouldLetAdminAndFinanceControllerRecordAndViewSales(String role) throws Exception {
        identityService.grant(companyId, role);

        String saleId = JsonPath.read(
                create().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        read(saleId).andExpect(status().isOk());
        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    // identity-service answers "not allowed" for INVITED and SUSPENDED memberships, inactive
    // accounts and inactive companies (tested in identity-service). Here: this service honours it.
    @Test
    void shouldDenyWhenIdentityServiceDoesNotAllowMembership() throws Exception {
        UUID existingSaleId = insertSale(yesterday.minusDays(1));

        create().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You do not have access to this company."));
        list().andExpect(status().isForbidden());
        read(existingSaleId.toString()).andExpect(status().isForbidden());

        assertThat(countSales()).isEqualTo(1);
    }

    @Test
    void shouldDenyWhenAllowedAnswerHasNoRole() throws Exception {
        identityService.reply(companyId, 200, "{\"allowed\":true,\"companyRole\":null}");

        create().andExpect(status().isForbidden());
        assertThat(countSales()).isZero();
    }

    @Test
    void shouldForwardUserTokenAndServiceKeyToIdentityService() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        list().andExpect(status().isOk());

        assertThat(identityService.lastAuthorization()).isEqualTo("Bearer " + USER_TOKEN);
        assertThat(identityService.lastServiceKey()).isEqualTo(identityServiceProperties.internalApiSecret());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 500, 503})
    void shouldFailClosedWhenIdentityServiceReturnsError(int identityStatus) throws Exception {
        identityService.reply(companyId, identityStatus, "{}");

        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Company access could not be verified. Please try again later."));
        list().andExpect(status().isServiceUnavailable());

        assertThat(countSales()).isZero();
    }

    @Test
    void shouldFailClosedWhenIdentityServiceResponseIsUnreadable() throws Exception {
        identityService.reply(companyId, 200, "{not json");

        create().andExpect(status().isServiceUnavailable());
        assertThat(countSales()).isZero();
    }

    @Test
    void shouldFailClosedWhenIdentityServiceReturnsUnknownRole() throws Exception {
        identityService.reply(companyId, 200, "{\"allowed\":true,\"companyRole\":\"SUPER_ADMIN\"}");

        create().andExpect(status().isServiceUnavailable());
        assertThat(countSales()).isZero();
    }

    @Test
    void shouldFailClosedWhenIdentityServiceTimesOut() throws Exception {
        // Would grant access, but answers after the 1s read timeout configured for tests.
        identityService.grantAfterDelay(companyId, "COMPANY_ADMIN", Duration.ofSeconds(3));

        create().andExpect(status().isServiceUnavailable());

        assertThat(countSales()).isZero();
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post(salesUrl())
                .with(userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"saleDate": "%s", "cashSalesAmount": 42000.00, "cardSalesAmount": 8000.00}
                        """.formatted(yesterday)));
    }

    private ResultActions read(String saleId) throws Exception {
        return mockMvc.perform(get(salesUrl() + "/" + saleId).with(userToken()));
    }

    private ResultActions list() throws Exception {
        return mockMvc.perform(get(salesUrl()).with(userToken()));
    }

    private static RequestPostProcessor userToken() {
        return jwt().jwt(jwt -> jwt.tokenValue(USER_TOKEN).subject(UUID.randomUUID().toString()));
    }

    private String salesUrl() {
        return "/api/v1/cash-management/companies/" + companyId + "/sales";
    }

    private UUID insertSale(LocalDate saleDate) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO daily_sales
                            (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                        VALUES (?, ?, 1000.00, 0.00, 'UI', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, saleDate, UUID.randomUUID());
    }

    private int countSales() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE company_id = ?", Integer.class, companyId);
    }
}
