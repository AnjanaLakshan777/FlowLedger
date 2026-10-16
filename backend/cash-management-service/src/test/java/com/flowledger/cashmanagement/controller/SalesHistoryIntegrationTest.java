package com.flowledger.cashmanagement.controller;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The approved sales history that forecast-service reads for its previous-year pattern. Membership
 * answers come from FakeIdentityServer. Needs the local cash-management database; each test is
 * rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SalesHistoryIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();

    @DynamicPropertySource
    static void identityServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID controller;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        controller = UUID.randomUUID();
        identityService.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
    }

    @Test
    void shouldListOnlyApprovedRowsIncludingZeroDays() throws Exception {
        approved("a.csv", "2025-01-01,100.00,50.50", "2025-01-02,0,0", "2025-01-03,,1");
        pending("b.csv", "2025-02-01,7,7");
        rejected("c.csv", "2025-03-01,8,8");
        upload("d.csv", "2025-04-01,9,9", "2025-04-01,9,9");   // invalid: duplicate dates
        // A manual entry is a daily sale, not imported history.
        jdbcTemplate.update("""
                INSERT INTO daily_sales (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                VALUES (?, DATE '2025-05-01', 5.00, 0.00, 'UI', ?)
                """, companyId, UUID.randomUUID());

        history("2024-12-01", "2025-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2024-12-01"))
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[*].saleDate", contains("2025-01-01", "2025-01-02")))
                .andExpect(jsonPath("$.days[0].cashSalesAmount").value(100.00))
                .andExpect(jsonPath("$.days[0].cardSalesAmount").value(50.50))
                .andExpect(jsonPath("$.days[0].importBatchId").exists())
                // Approved with zero sales: present as a known zero, although it has no daily_sales record.
                .andExpect(jsonPath("$.days[1].cashSalesAmount").value(0.0))
                .andExpect(jsonPath("$.days[1].cardSalesAmount").value(0.0));
    }

    @Test
    void shouldLimitTheRangeInclusively() throws Exception {
        approved("a.csv", "2025-01-01,1,1", "2025-01-02,2,2", "2025-01-03,3,3");

        history("2025-01-02", "2025-01-02").andExpect(jsonPath("$.days[*].saleDate", contains("2025-01-02")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?from=2025-01-02&to=2025-01-01", "?from=2025-01-01", "?to=2025-01-01", "",
            "?from=2023-01-01&to=2025-12-31", "?from=x&to=2025-01-01"})
    void shouldRejectInvalidRanges(String query) throws Exception {
        mockMvc.perform(get(historyUrl(companyId) + query).with(user(controller))).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "COMPANY_ADMIN", "FINANCE_CONTROLLER"})
    void shouldLetForecastRolesReadTheWholeCompanysHistory(String role) throws Exception {
        approved("a.csv", "2025-01-01,1,1");
        identityService.grant(companyId, role);

        // An Accountant sees only their own imports on the import screen, but the forecast needs all.
        mockMvc.perform(get(historyUrl(companyId) + "?from=2025-01-01&to=2025-01-31").with(user(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(1)));
    }

    @Test
    void shouldKeepCashiersAndOtherCompaniesOut() throws Exception {
        approved("a.csv", "2025-01-01,1,1");
        identityService.grant(companyId, "CASHIER");
        history("2025-01-01", "2025-01-31").andExpect(status().isForbidden());

        UUID other = UUID.randomUUID();
        identityService.grant(other, "FINANCE_CONTROLLER");
        mockMvc.perform(get(historyUrl(other) + "?from=2025-01-01&to=2025-01-31").with(user(controller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(0)));
        mockMvc.perform(get(historyUrl(UUID.randomUUID()) + "?from=2025-01-01&to=2025-01-31").with(user(controller)))
                .andExpect(status().isForbidden());
    }

    // Helpers

    private void approved(String filename, String... rows) throws Exception {
        String id = upload(filename, rows);
        mockMvc.perform(post(importsUrl() + "/" + id + "/approve").with(user(controller))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1, \"excludeInvalidRows\": true}"))
                .andExpect(status().isOk());
    }

    private void pending(String filename, String... rows) throws Exception {
        upload(filename, rows);
    }

    private void rejected(String filename, String... rows) throws Exception {
        String id = upload(filename, rows);
        mockMvc.perform(post(importsUrl() + "/" + id + "/reject").with(user(controller))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1, \"reason\": \"Wrong\"}"))
                .andExpect(status().isOk());
    }

    private String upload(String filename, String... rows) throws Exception {
        String content = "date,cash_sales,card_payments\n" + String.join("\n", rows) + "\n";
        String body = mockMvc.perform(multipart(importsUrl())
                        .file(new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                        .with(user(controller)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.batch.id");
    }

    private ResultActions history(String from, String to) throws Exception {
        return mockMvc.perform(get(historyUrl(companyId) + "?from=" + from + "&to=" + to).with(user(controller)));
    }

    private String importsUrl() {
        return "/api/v1/cash-management/companies/" + companyId + "/sales-imports";
    }

    private static String historyUrl(UUID company) {
        return "/api/v1/cash-management/companies/" + company + "/sales-history";
    }

    private static RequestPostProcessor user(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }
}
