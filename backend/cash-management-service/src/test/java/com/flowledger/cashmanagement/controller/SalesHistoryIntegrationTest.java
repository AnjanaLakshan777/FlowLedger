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
 * The recorded sales history (approved imports and manual entries) that forecast-service reads for
 * its previous-year pattern and growth ratio. Membership
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

    @org.springframework.beans.factory.annotation.Value("${flowledger.security.internal-api.secret}")
    private String internalKey;

    private static final String KEY_HEADER = "X-Internal-Api-Key";

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
    void shouldListApprovedRowsIncludingZeroDaysAndManualEntries() throws Exception {
        approved("a.csv", "2025-01-01,100.00,50.50", "2025-01-02,0,0", "2025-01-03,,1");
        pending("b.csv", "2025-02-01,7,7");
        rejected("c.csv", "2025-03-01,8,8");
        upload("d.csv", "2025-04-01,9,9", "2025-04-01,9,9");   // invalid: duplicate dates
        manual("2025-05-01", "5.00", "0.00");

        history("2024-12-01", "2025-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2024-12-01"))
                .andExpect(jsonPath("$.days", hasSize(3)))
                .andExpect(jsonPath("$.days[*].saleDate", contains("2025-01-01", "2025-01-02", "2025-05-01")))
                .andExpect(jsonPath("$.days[0].cashSalesAmount").value(100.00))
                .andExpect(jsonPath("$.days[0].cardSalesAmount").value(50.50))
                .andExpect(jsonPath("$.days[0].importBatchId").exists())
                .andExpect(jsonPath("$.days[0].entrySource").value("HISTORICAL_IMPORT"))
                // Approved with zero sales: present as a known zero, although it has no daily_sales record.
                .andExpect(jsonPath("$.days[1].cashSalesAmount").value(0.0))
                .andExpect(jsonPath("$.days[1].cardSalesAmount").value(0.0))
                // Entered on the Daily Sales screen: no import batch.
                .andExpect(jsonPath("$.days[2].cashSalesAmount").value(5.00))
                .andExpect(jsonPath("$.days[2].importBatchId").doesNotExist())
                .andExpect(jsonPath("$.days[2].entrySource").value("UI"));
    }

    @Test
    void shouldListAnApprovedZeroDayOnceWhenAManualEntryHasTheSameDate() throws Exception {
        approved("a.csv", "2025-01-02,0,0");
        manual("2025-01-02", "5.00", "0.00");

        history("2025-01-01", "2025-01-31")
                .andExpect(jsonPath("$.days", hasSize(1)))
                .andExpect(jsonPath("$.days[0].entrySource").value("HISTORICAL_IMPORT"))
                .andExpect(jsonPath("$.days[0].cashSalesAmount").value(0.0));
    }

    @Test
    void shouldNotListAnotherCompanysManualEntries() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO daily_sales (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                VALUES (?, DATE '2025-05-01', 5.00, 0.00, 'UI', ?)
                """, UUID.randomUUID(), UUID.randomUUID());

        history("2025-05-01", "2025-05-31").andExpect(jsonPath("$.days", hasSize(0)));
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

    // Internal API for forecast-service's daily scheduled forecast

    @Test
    void shouldServeTheSameHistoryThroughTheInternalApiWithTheKey() throws Exception {
        approved("a.csv", "2025-01-01,100.00,50.50");
        manual("2025-01-02", "5.00", "0.00");

        mockMvc.perform(get(internalUrl(companyId) + "?from=2025-01-01&to=2025-01-31").header(KEY_HEADER, internalKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(2)))
                .andExpect(jsonPath("$.days[1].entrySource").value("UI"));
        mockMvc.perform(get(internalUrl(companyId) + "?from=2025-01-31&to=2025-01-01").header(KEY_HEADER, internalKey))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRefuseTheInternalApiWithoutTheKeyOrWithAUserToken() throws Exception {
        mockMvc.perform(get(internalUrl(companyId) + "?from=2025-01-01&to=2025-01-31")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(internalUrl(companyId) + "?from=2025-01-01&to=2025-01-31").with(user(controller)))
                .andExpect(status().isUnauthorized());
    }

    private static String internalUrl(UUID company) {
        return "/internal/v1/cash-management/companies/" + company + "/sales-history";
    }

    // Helpers

    private void approved(String filename, String... rows) throws Exception {
        String id = upload(filename, rows);
        mockMvc.perform(post(importsUrl() + "/" + id + "/approve").with(user(controller))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1, \"excludeInvalidRows\": true}"))
                .andExpect(status().isOk());
    }

    private void manual(String saleDate, String cash, String card) {
        jdbcTemplate.update("""
                INSERT INTO daily_sales (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                VALUES (?, ?::date, ?::numeric, ?::numeric, 'UI', ?)
                """, companyId, saleDate, cash, card, UUID.randomUUID());
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
