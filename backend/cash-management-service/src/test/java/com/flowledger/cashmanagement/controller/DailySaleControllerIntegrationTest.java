package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the daily sales API for a COMPANY_ADMIN of one company. Membership answers come from
 * FakeIdentityServer over HTTP; role-by-role rules are in DailySaleAuthorizationIntegrationTest.
 * Needs the local cash-management PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DailySaleControllerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    @DynamicPropertySource
    static void identityServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID companyId;
    private UUID otherCompanyId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        // Random IDs so the tests never collide with data in the local dev database.
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        today = LocalDate.now(COLOMBO);

        identityService.reset();
        identityService.grant(companyId, "COMPANY_ADMIN");
    }

    @Test
    void shouldCreateDailySale() throws Exception {
        LocalDate yesterday = today.minusDays(1);

        String body = create(companyId, requestJson(Map.of(
                "saleDate", yesterday.toString(), "cashSalesAmount", "30000.50", "cardSalesAmount", "19999.5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.saleDate").value(yesterday.toString()))
                .andExpect(jsonPath("$.entrySource").value("UI"))
                .andExpect(jsonPath("$.recordedBy").value(userId.toString()))
                .andExpect(jsonPath("$.recordedAt", notNullValue()))
                .andExpect(content().string(containsString("\"cashSalesAmount\":30000.50")))
                .andExpect(content().string(containsString("\"cardSalesAmount\":19999.50")))
                .andExpect(content().string(containsString("\"totalSalesAmount\":50000.00")))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM daily_sales WHERE id = ?", id);
        assertThat(row.get("company_id")).isEqualTo(companyId);
        assertThat(row.get("sale_date").toString()).isEqualTo(yesterday.toString());
        assertThat((BigDecimal) row.get("cash_sales_amount")).isEqualByComparingTo("30000.50");
        assertThat((BigDecimal) row.get("card_sales_amount")).isEqualByComparingTo("19999.50");
        assertThat(row.get("entry_source")).isEqualTo("UI");
        assertThat(row.get("recorded_by")).isEqualTo(userId);
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("updated_by")).isNull();
    }

    @Test
    void shouldAcceptPastSaleDateAndRecordEntryTimeSeparately() throws Exception {
        LocalDate pastSaleDate = LocalDate.of(2024, 3, 15);

        String body = create(companyId, requestJson(Map.of("saleDate", pastSaleDate.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleDate").value("2024-03-15"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT sale_date, recorded_at FROM daily_sales WHERE id = ?",
                UUID.fromString(JsonPath.read(body, "$.id")));
        assertThat(row.get("sale_date").toString()).isEqualTo("2024-03-15");
        Instant recordedAt = ((Timestamp) row.get("recorded_at")).toInstant();
        assertThat(recordedAt).isBetween(Instant.now().minus(Duration.ofMinutes(1)), Instant.now().plusSeconds(5));
    }

    @Test
    void shouldAcceptTodayAsSaleDate() throws Exception {
        create(companyId, requestJson(Map.of("saleDate", today.toString())))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectFutureSaleDate() throws Exception {
        create(companyId, requestJson(Map.of("saleDate", today.plusDays(1).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.saleDate").value("saleDate must not be in the future"));

        assertThat(countSales(companyId)).isZero();
    }

    @Test
    void shouldIgnoreOwnershipAndAuditFieldsSentByClient() throws Exception {
        UUID someoneElse = UUID.randomUUID();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("id", UUID.randomUUID().toString());
        overrides.put("companyId", otherCompanyId.toString());
        overrides.put("recordedBy", someoneElse.toString());
        overrides.put("recordedAt", "2020-01-01T00:00:00Z");
        overrides.put("entrySource", "HISTORICAL_IMPORT");

        String body = create(companyId, requestJson(overrides))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM daily_sales WHERE id = ?", UUID.fromString(JsonPath.read(body, "$.id")));
        assertThat(row.get("company_id")).isEqualTo(companyId);
        assertThat(row.get("recorded_by")).isEqualTo(userId);
        assertThat(row.get("entry_source")).isEqualTo("UI");
        assertThat(((Timestamp) row.get("recorded_at")).toInstant()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
        assertThat(countSales(otherCompanyId)).isZero();
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("saleDate", null, "saleDate is required"),
                Arguments.of("cashSalesAmount", null, "cashSalesAmount is required"),
                Arguments.of("cardSalesAmount", null, "cardSalesAmount is required"),
                Arguments.of("cashSalesAmount", "-1.00", "cashSalesAmount must not be negative"),
                Arguments.of("cardSalesAmount", "-0.01", "cardSalesAmount must not be negative"),
                Arguments.of("cashSalesAmount", "100.005",
                        "cashSalesAmount must have at most 17 digits before and 2 after the decimal point"),
                Arguments.of("cardSalesAmount", "100000000000000000",
                        "cardSalesAmount must have at most 17 digits before and 2 after the decimal point")
        );
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidFields")
    void shouldRejectInvalidField(String field, Object value, String message) throws Exception {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(field, value);

        create(companyId, requestJson(overrides))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors." + field).value(message));

        assertThat(countSales(companyId)).isZero();
    }

    @Test
    void shouldRejectImpossibleCalendarDate() throws Exception {
        create(companyId, requestJson(Map.of("saleDate", "2026-02-30")))
                .andExpect(status().isBadRequest());

        assertThat(countSales(companyId)).isZero();
    }

    @Test
    void shouldRejectRecordWithoutAnySales() throws Exception {
        create(companyId, requestJson(Map.of("cashSalesAmount", "0", "cardSalesAmount", "0.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.totalSalesAmount")
                        .value("cashSalesAmount and cardSalesAmount must not both be zero"));

        assertThat(countSales(companyId)).isZero();
    }

    @Test
    void shouldAcceptRecordWithOnlyCardSales() throws Exception {
        create(companyId, requestJson(Map.of("cashSalesAmount", "0", "cardSalesAmount", "1250.00")))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"totalSalesAmount\":1250.00")));
    }

    @Test
    void shouldRejectSecondRecordForSameSaleDate() throws Exception {
        LocalDate saleDate = today.minusDays(3);
        insertSale(companyId, saleDate, "1000.00", "0.00");

        create(companyId, requestJson(Map.of("saleDate", saleDate.toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Sales for this date have already been recorded."));

        assertThat(countSales(companyId)).isEqualTo(1);
    }

    @Test
    void shouldReturnExistingSale() throws Exception {
        UUID saleId = insertSale(companyId, today.minusDays(2), "12500.00", "7500.25");

        mockMvc.perform(authenticated(get(salesUrl(companyId) + "/" + saleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saleId.toString()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.saleDate").value(today.minusDays(2).toString()))
                .andExpect(content().string(containsString("\"totalSalesAmount\":20000.25")));
    }

    @Test
    void shouldReturnNotFoundForUnknownSale() throws Exception {
        mockMvc.perform(authenticated(get(salesUrl(companyId) + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Sales record not found."));
    }

    @Test
    void shouldListOnlyTheRequestedCompanysSalesNewestFirst() throws Exception {
        UUID older = insertSale(companyId, LocalDate.of(2026, 9, 1), "100.00", "0.00");
        UUID newer = insertSale(companyId, LocalDate.of(2026, 9, 2), "200.00", "0.00");
        insertSale(otherCompanyId, LocalDate.of(2026, 9, 3), "300.00", "0.00");

        mockMvc.perform(authenticated(get(salesUrl(companyId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id").value(contains(newer.toString(), older.toString())));
    }

    @Test
    void shouldFilterSalesByInclusiveDateRange() throws Exception {
        insertSale(companyId, LocalDate.of(2026, 9, 1), "100.00", "0.00");
        insertSale(companyId, LocalDate.of(2026, 9, 2), "200.00", "0.00");
        insertSale(companyId, LocalDate.of(2026, 9, 3), "300.00", "0.00");
        insertSale(companyId, LocalDate.of(2026, 9, 4), "400.00", "0.00");

        assertSaleDates("?from=2026-09-02&to=2026-09-03", "2026-09-03", "2026-09-02");
        assertSaleDates("?from=2026-09-03", "2026-09-04", "2026-09-03");
        assertSaleDates("?to=2026-09-01", "2026-09-01");
        assertSaleDates("?from=2026-09-02&to=2026-09-02", "2026-09-02");
    }

    @Test
    void shouldRejectDateRangeEndingBeforeItStarts() throws Exception {
        mockMvc.perform(authenticated(get(salesUrl(companyId) + "?from=2026-09-05&to=2026-09-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").value("to must not be before from"));
    }

    @Test
    void shouldRejectInvalidDateFilter() throws Exception {
        mockMvc.perform(authenticated(get(salesUrl(companyId) + "?from=05-09-2026")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRefuseAnotherCompanysSales() throws Exception {
        UUID otherSaleId = insertSale(otherCompanyId, today.minusDays(1), "100.00", "0.00");

        mockMvc.perform(authenticated(get(salesUrl(otherCompanyId))))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(get(salesUrl(otherCompanyId) + "/" + otherSaleId)))
                .andExpect(status().isForbidden());
        create(otherCompanyId, requestJson(Map.of()))
                .andExpect(status().isForbidden());

        assertThat(countSales(otherCompanyId)).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidUuidPathParameters() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/cash-management/companies/not-a-uuid/sales")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authenticated(post("/api/v1/cash-management/companies/not-a-uuid/sales"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authenticated(get(salesUrl(companyId) + "/not-a-uuid")))
                .andExpect(status().isBadRequest());

        // Rejected before any membership check or database access.
        assertThat(identityService.lastAuthorization()).isNull();
        assertThat(countSales(companyId)).isZero();
    }

    @Test
    void shouldNotFindAnotherCompanysSaleThroughOwnCompanyPath() throws Exception {
        UUID otherSaleId = insertSale(otherCompanyId, today.minusDays(1), "100.00", "0.00");

        mockMvc.perform(authenticated(get(salesUrl(companyId) + "/" + otherSaleId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(salesUrl(companyId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(salesUrl(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isUnauthorized());

        assertThat(countSales(companyId)).isZero();
        assertThat(identityService.lastAuthorization()).isNull();
    }

    // Recording sales must not create, or look like, a bank-confirmed inflow: this service owns
    // no bank data, and the record carries no deposit or inflow information.
    @Test
    void shouldRecordSalesWithoutCreatingBankData() throws Exception {
        create(companyId, requestJson(Map.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankDepositDate").doesNotExist())
                .andExpect(jsonPath("$.depositedAmount").doesNotExist());

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class);
        // This service owns sales and their imports only; bank deposits live in bank-integration-service.
        assertThat(tables).containsExactly("cash_management_audit_events", "daily_sales", "flyway_schema_history",
                "sales_import_batches", "sales_import_rows");
        assertThat(countSales(companyId)).isEqualTo(1);
    }

    // Corrections

    @Test
    void shouldCorrectAManualEntryAndAuditIt() throws Exception {
        UUID saleId = insertSale(companyId, today.minusDays(2), "35000.00", "15000.00");

        update(saleId, "36000.50", "15000.00", "  Cash count was wrong  ", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSalesAmount").value(36000.50))
                .andExpect(jsonPath("$.totalSalesAmount").value(51000.50))
                .andExpect(jsonPath("$.saleDate").value(today.minusDays(2).toString()))
                .andExpect(jsonPath("$.updatedBy").value(userId.toString()))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(2));

        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT action, actor_user_id, reason, before_data ->> 'cashSalesAmount' AS before_cash,
                       after_data ->> 'cashSalesAmount' AS after_cash
                FROM cash_management_audit_events WHERE entity_id = ?
                """, saleId);
        assertThat(audit).containsEntry("action", "DAILY_SALE_CORRECTED").containsEntry("actor_user_id", userId)
                .containsEntry("reason", "Cash count was wrong").containsEntry("before_cash", "35000.00")
                .containsEntry("after_cash", "36000.50");
    }

    @Test
    void shouldRefuseStaleImportedOrEmptyCorrections() throws Exception {
        UUID saleId = insertSale(companyId, today.minusDays(2), "35000.00", "15000.00");

        update(saleId, "1.00", "0.00", "Wrong", 2).andExpect(status().isConflict());
        update(saleId, "0.00", "0.00", "Wrong", 1).andExpect(status().isBadRequest());
        update(saleId, "1.00", "0.00", " ", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("reason must not be blank"));
        update(UUID.randomUUID(), "1.00", "0.00", "Wrong", 1).andExpect(status().isNotFound());

        UUID batchId = jdbcTemplate.queryForObject("""
                INSERT INTO sales_import_batches (company_id, original_filename, file_sha256, file_size_bytes, status,
                    row_count, valid_row_count, invalid_row_count, duplicate_row_count, cash_sales_total, card_sales_total,
                    uploaded_by, approved_by, approved_at, imported_row_count, excluded_row_count)
                VALUES (?, 'a.csv', repeat('a', 64), 10, 'APPROVED', 1, 1, 0, 0, 1, 1, ?, ?, now(), 1, 0)
                RETURNING id
                """, UUID.class, companyId, UUID.randomUUID(), UUID.randomUUID());
        UUID imported = jdbcTemplate.queryForObject("""
                INSERT INTO daily_sales (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source,
                                         recorded_by, import_batch_id)
                VALUES (?, ?, 1, 1, 'HISTORICAL_IMPORT', ?, ?) RETURNING id
                """, UUID.class, companyId, today.minusDays(400), UUID.randomUUID(), batchId);
        update(imported, "2.00", "1.00", "Wrong", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("reversing the import")));
    }

    @Test
    void shouldLetOnlyTheAccountantAndAboveCorrect() throws Exception {
        UUID saleId = insertSale(companyId, today.minusDays(2), "35000.00", "15000.00");
        identityService.reset();
        identityService.grant(companyId, "CASHIER");
        update(saleId, "1.00", "0.00", "Wrong", 1).andExpect(status().isForbidden());

        identityService.reset();
        identityService.grant(companyId, "ACCOUNTANT");
        update(saleId, "1.00", "0.00", "Wrong", 1).andExpect(status().isOk());
    }

    private ResultActions update(UUID saleId, String cash, String card, String reason, int version) throws Exception {
        return mockMvc.perform(authenticated(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(salesUrl(companyId) + "/" + saleId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("cashSalesAmount", cash, "cardSalesAmount", card,
                        "reason", reason, "version", version))));
    }

    private void assertSaleDates(String query, String... expectedDates) throws Exception {
        mockMvc.perform(authenticated(get(salesUrl(companyId) + query)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].saleDate").value(contains((Object[]) expectedDates)));
    }

    private ResultActions create(UUID companyId, String body) throws Exception {
        return mockMvc.perform(authenticated(post(salesUrl(companyId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(jwt -> jwt.subject(userId.toString())));
    }

    private static String salesUrl(UUID companyId) {
        return "/api/v1/cash-management/companies/" + companyId + "/sales";
    }

    private String requestJson(Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("saleDate", today.minusDays(1).toString());
        body.put("cashSalesAmount", "35000.00");
        body.put("cardSalesAmount", "15000.00");
        body.putAll(overrides);
        return JSON.writeValueAsString(body);
    }

    private UUID insertSale(UUID companyId, LocalDate saleDate, String cash, String card) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO daily_sales
                            (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                        VALUES (?, ?, ?, ?, 'UI', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, saleDate, new BigDecimal(cash), new BigDecimal(card), UUID.randomUUID());
    }

    private int countSales(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE company_id = ?", Integer.class, companyId);
    }
}
