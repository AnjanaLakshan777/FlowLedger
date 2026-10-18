package com.flowledger.payment.controller;

import com.flowledger.payment.support.FakeBankIntegrationServer;
import com.flowledger.payment.support.FakeIdentityServer;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the cheque API for a COMPANY_ADMIN of one company that has an active current account.
 * Membership answers and the bank account come from fake services over HTTP; role-by-role rules
 * are in ChequeAuthorizationIntegrationTest. Needs the local payment PostgreSQL database.
 * Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChequeControllerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeBankIntegrationServer bankIntegration = FakeBankIntegrationServer.instance();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
        registry.add("flowledger.bank-integration.base-url", bankIntegration::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID companyId;
    private UUID otherCompanyId;
    private UUID bankAccountId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        // Random IDs so the tests never collide with data in the local dev database.
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();
        today = LocalDate.now(ZoneId.of("Asia/Colombo"));

        identityService.reset();
        identityService.grant(companyId, "COMPANY_ADMIN");
        bankIntegration.reset();
        bankIntegration.currentAccount(companyId, bankAccountId);
    }

    @Test
    void shouldAddChequeWithAuditAndOutboxEvent() throws Exception {
        String body = add(requestJson(Map.of("chequeNumber", "004512")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.bankAccountId").value(bankAccountId.toString()))
                .andExpect(jsonPath("$.chequeNumber").value("004512"))
                .andExpect(jsonPath("$.payeeName").value("Kelani Valley Foods"))
                .andExpect(jsonPath("$.category").value("SUPPLIER_PAYMENT"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.createdBy").value(userId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(content().string(containsString("\"amount\":125000.50")))
                .andReturn().getResponse().getContentAsString();
        UUID chequeId = UUID.fromString(JsonPath.read(body, "$.id"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM cheques WHERE id = ?", chequeId);
        assertThat(row.get("cheque_number")).isEqualTo("004512");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("125000.50");
        assertThat(row.get("status")).isEqualTo("ISSUED");
        assertThat(row.get("version")).isEqualTo(1);

        Map<String, Object> audit = auditEvents(chequeId).getFirst();
        assertThat(audit.get("action")).isEqualTo("CHEQUE_ISSUED");
        assertThat(audit.get("actor_user_id")).isEqualTo(userId);
        assertThat(audit.get("company_id")).isEqualTo(companyId);
        assertThat(audit.get("before_status")).isNull();
        assertThat(audit.get("after_status")).isEqualTo("ISSUED");
        assertThat(audit.get("after_amount")).isEqualTo("125000.50");

        Map<String, Object> outbox = outboxEvents(chequeId).getFirst();
        assertThat(outbox.get("event_type")).isEqualTo("ChequeIssued");
        assertThat(outbox.get("published_at")).isNull();
        assertThat((String) outbox.get("payload")).contains("\"expectedClearingDate\": \"" + today.plusDays(7) + "\"")
                .contains("\"companyId\": \"" + companyId + "\"")
                .doesNotContain("Kelani Valley Foods");
    }

    @Test
    void shouldForwardUserTokenToBankIntegration() throws Exception {
        mockMvc.perform(post(chequesUrl(companyId))
                        .with(jwt().jwt(jwt -> jwt.tokenValue("user-access-token").subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isCreated());

        assertThat(bankIntegration.lastAuthorization()).isEqualTo("Bearer user-access-token");
    }

    @Test
    void shouldIgnoreServerControlledFieldsSentByClient() throws Exception {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("companyId", otherCompanyId.toString());
        overrides.put("bankAccountId", UUID.randomUUID().toString());
        overrides.put("status", "CANCELLED");
        overrides.put("createdBy", UUID.randomUUID().toString());
        overrides.put("version", 7);

        add(requestJson(overrides))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.bankAccountId").value(bankAccountId.toString()))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.createdBy").value(userId.toString()))
                .andExpect(jsonPath("$.version").value(1));
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("chequeNumber", "12345", "chequeNumber must be exactly 6 digits"),
                Arguments.of("chequeNumber", "12A456", "chequeNumber must be exactly 6 digits"),
                Arguments.of("chequeNumber", null, "chequeNumber is required"),
                Arguments.of("payeeName", "   ", "payeeName must not be blank"),
                Arguments.of("payeeName", "P".repeat(201), "payeeName must be at most 200 characters"),
                Arguments.of("category", null, "category is required"),
                Arguments.of("amount", "0", "amount must be greater than 0"),
                Arguments.of("amount", "-5.00", "amount must be greater than 0"),
                Arguments.of("amount", "10.005", "amount must have at most 17 digits before and 2 after the decimal point"),
                Arguments.of("issueDate", null, "issueDate is required"),
                Arguments.of("expectedClearingDate", null, "expectedClearingDate is required"),
                Arguments.of("notes", "N".repeat(2001), "notes must be at most 2000 characters")
        );
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidFields")
    void shouldRejectInvalidField(String field, Object value, String message) throws Exception {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(field, value);

        add(requestJson(overrides))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).value(message));

        assertThat(countCheques(companyId)).isZero();
    }

    @Test
    void shouldRequireAndKeepTheDescriptionOfAnOtherExpensesCheque() throws Exception {
        add(requestJson(Map.of("category", "OTHER_EXPENSES")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.otherDescription")
                        .value("otherDescription is required when the category is OTHER_EXPENSES"));
        add(requestJson(Map.of("category", "OTHER_EXPENSES", "otherDescription", "   ")))
                .andExpect(status().isBadRequest());
        add(requestJson(Map.of("category", "OTHER_EXPENSES", "otherDescription", "A".repeat(121))))
                .andExpect(jsonPath("$.errors.otherDescription").value("otherDescription must be at most 120 characters"));
        assertThat(countCheques(companyId)).isZero();

        String body = add(requestJson(Map.of("category", "OTHER_EXPENSES", "otherDescription", "  Audit fee ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.otherDescription").value("Audit fee"))
                .andReturn().getResponse().getContentAsString();
        UUID chequeId = UUID.fromString(JsonPath.read(body, "$.id"));

        // The description is free text, so it stays out of the outbox event like the payee and notes.
        assertThat(outboxEvents(chequeId).getFirst().get("payload").toString()).doesNotContain("Audit fee");

        // Another category drops the description.
        update(chequeId, requestJson(Map.of("category", "TAX", "otherDescription", "Audit fee", "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("TAX"))
                .andExpect(jsonPath("$.otherDescription").doesNotExist());
    }

    @Test
    void shouldRejectUnknownCategory() throws Exception {
        add(requestJson(Map.of("category", "RENT"))).andExpect(status().isBadRequest());
        assertThat(countCheques(companyId)).isZero();
    }

    @Test
    void shouldRejectClearingDateBeforeIssueDate() throws Exception {
        add(requestJson(Map.of("issueDate", today.plusDays(5).toString(),
                "expectedClearingDate", today.plusDays(4).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expectedClearingDate")
                        .value("expectedClearingDate must not be before issueDate"));
    }

    @Test
    void shouldRejectClearingDateThatIsNotAfterToday() throws Exception {
        add(requestJson(Map.of("issueDate", today.minusDays(3).toString(), "expectedClearingDate", today.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expectedClearingDate").value("expectedClearingDate must be after today"));

        assertThat(countCheques(companyId)).isZero();
    }

    @Test
    void shouldAcceptPastIssueDateWithFutureClearingDate() throws Exception {
        add(requestJson(Map.of("issueDate", today.minusDays(10).toString())))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectDuplicateChequeNumber() throws Exception {
        add(requestJson(Map.of("chequeNumber", "000777"))).andExpect(status().isCreated());

        add(requestJson(Map.of("chequeNumber", "000777", "payeeName", "Someone else")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("This cheque number is already recorded for the company's current account."));

        assertThat(countCheques(companyId)).isEqualTo(1);
    }

    @Test
    void shouldRefuseChequeWhenCompanyHasNoActiveCurrentAccount() throws Exception {
        bankIntegration.reply(companyId, 200, "[]");

        add(requestJson(Map.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("The company has no active current account. Set one up before recording cheques."));

        assertThat(countCheques(companyId)).isZero();
    }

    @Test
    void shouldFailClosedWhenBankIntegrationFails() throws Exception {
        bankIntegration.reply(companyId, 500, "{}");

        add(requestJson(Map.of())).andExpect(status().isServiceUnavailable());

        assertThat(countCheques(companyId)).isZero();
    }

    @Test
    void shouldReturnExistingCheque() throws Exception {
        UUID chequeId = createCheque("004600");

        mockMvc.perform(authenticated(get(chequesUrl(companyId) + "/" + chequeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chequeId.toString()))
                .andExpect(jsonPath("$.chequeNumber").value("004600"));
    }

    @Test
    void shouldReturnNotFoundForUnknownChequeAndOtherCompanysCheque() throws Exception {
        UUID otherCheque = insertCheque(otherCompanyId, "000001", "ISSUED", today.plusDays(5));

        mockMvc.perform(authenticated(get(chequesUrl(companyId) + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Cheque not found."));
        mockMvc.perform(authenticated(get(chequesUrl(companyId) + "/" + otherCheque)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldListCompanyChequesWithFilters() throws Exception {
        insertCheque(companyId, "000010", "ISSUED", today.plusDays(10));
        insertCheque(companyId, "000003", "ISSUED", today.plusDays(3));
        insertCheque(companyId, "000005", "CANCELLED", today.plusDays(5));
        insertCheque(otherCompanyId, "000004", "ISSUED", today.plusDays(4));

        assertChequeNumbers("", "000003", "000005", "000010");
        assertChequeNumbers("?status=ISSUED", "000003", "000010");
        assertChequeNumbers("?status=CANCELLED", "000005");
        assertChequeNumbers("?clearingFrom=" + today.plusDays(4) + "&clearingTo=" + today.plusDays(10),
                "000005", "000010");
    }

    @Test
    void shouldRejectClearingRangeEndingBeforeItStarts() throws Exception {
        mockMvc.perform(authenticated(get(chequesUrl(companyId) + "?clearingFrom=" + today.plusDays(5)
                        + "&clearingTo=" + today)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.clearingTo").value("clearingTo must not be before clearingFrom"));
    }

    // Overdue cheques are never marked paid automatically: they stay ISSUED.
    @Test
    void shouldKeepChequePastItsClearingDateIssued() throws Exception {
        UUID overdue = insertCheque(companyId, "000099", "ISSUED", today.minusDays(5));

        mockMvc.perform(authenticated(get(chequesUrl(companyId) + "/" + overdue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    @Test
    void shouldUpdateChequeAndRecordBeforeAndAfter() throws Exception {
        UUID chequeId = createCheque("004700");

        update(chequeId, requestJson(Map.of("chequeNumber", "004701", "amount", "99000.00",
                "expectedClearingDate", today.plusDays(14).toString(), "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chequeNumber").value("004701"))
                .andExpect(jsonPath("$.expectedClearingDate").value(today.plusDays(14).toString()))
                .andExpect(jsonPath("$.updatedBy").value(userId.toString()))
                .andExpect(jsonPath("$.updatedAt", notNullValue()))
                .andExpect(jsonPath("$.version").value(2));

        List<Map<String, Object>> events = auditEvents(chequeId);
        assertThat(events).hasSize(2);
        Map<String, Object> updated = events.get(1);
        assertThat(updated.get("action")).isEqualTo("CHEQUE_UPDATED");
        assertThat(updated.get("before_amount")).isEqualTo("125000.50");
        assertThat(updated.get("after_amount")).isEqualTo("99000.00");
        assertThat(updated.get("before_number")).isEqualTo("004700");
        assertThat(updated.get("after_number")).isEqualTo("004701");
        assertThat(outboxEvents(chequeId)).extracting(event -> event.get("event_type"))
                .containsExactly("ChequeIssued", "ChequeUpdated");
    }

    @Test
    void shouldNotRecordAnythingForUnchangedUpdate() throws Exception {
        UUID chequeId = createCheque("004750");

        update(chequeId, requestJson(Map.of("chequeNumber", "004750", "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        assertThat(auditEvents(chequeId)).hasSize(1);
        assertThat(outboxEvents(chequeId)).hasSize(1);
    }

    @Test
    void shouldRejectUpdateWithStaleOrMissingVersion() throws Exception {
        UUID chequeId = createCheque("004800");
        update(chequeId, requestJson(Map.of("chequeNumber", "004800", "amount", "1.00", "version", 1)))
                .andExpect(status().isOk());

        // A second user still holding version 1 must not overwrite the first change.
        update(chequeId, requestJson(Map.of("chequeNumber", "004800", "amount", "2.00", "version", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The cheque was changed by someone else. Reload it and try again."));
        update(chequeId, requestJson(Map.of("chequeNumber", "004800")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.version").value("version is required"));

        assertThat((BigDecimal) jdbcTemplate.queryForObject(
                "SELECT amount FROM cheques WHERE id = ?", BigDecimal.class, chequeId)).isEqualByComparingTo("1.00");
    }

    @Test
    void shouldRejectUpdateToDuplicateChequeNumber() throws Exception {
        createCheque("004901");
        UUID second = createCheque("004902");

        update(second, requestJson(Map.of("chequeNumber", "004901", "version", 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRefuseToChangeChequeThatIsNoLongerUpcoming() throws Exception {
        UUID overdue = insertCheque(companyId, "000098", "ISSUED", today);
        UUID cancelled = insertCheque(companyId, "000097", "CANCELLED", today.plusDays(5));

        update(overdue, requestJson(Map.of("chequeNumber", "000098", "version", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Only issued cheques whose expected clearing date is after today can be changed or cancelled."));
        cancel(overdue, "Paid by bank transfer instead", 1).andExpect(status().isConflict());
        update(cancelled, requestJson(Map.of("chequeNumber", "000097", "version", 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldCancelChequeAndRecordActorAndReason() throws Exception {
        UUID chequeId = createCheque("005000");

        cancel(chequeId, "  Paid by bank transfer instead  ", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.updatedBy").value(userId.toString()))
                .andExpect(jsonPath("$.version").value(2));

        Map<String, Object> cancelled = auditEvents(chequeId).get(1);
        assertThat(cancelled.get("action")).isEqualTo("CHEQUE_CANCELLED");
        assertThat(cancelled.get("actor_user_id")).isEqualTo(userId);
        assertThat(cancelled.get("reason")).isEqualTo("Paid by bank transfer instead");
        assertThat(cancelled.get("before_status")).isEqualTo("ISSUED");
        assertThat(cancelled.get("after_status")).isEqualTo("CANCELLED");
        assertThat(outboxEvents(chequeId)).extracting(event -> event.get("event_type"))
                .containsExactly("ChequeIssued", "ChequeCancelled");

        // Cancelled cheques stay on record but are no longer issued payments.
        assertChequeNumbers("?status=ISSUED");
        assertChequeNumbers("?status=CANCELLED", "005000");
    }

    @Test
    void shouldRequireReasonAndVersionToCancel() throws Exception {
        UUID chequeId = createCheque("005100");

        mockMvc.perform(authenticated(post(chequesUrl(companyId) + "/" + chequeId + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"  \", \"version\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("reason must not be blank"));
        mockMvc.perform(authenticated(post(chequesUrl(companyId) + "/" + chequeId + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Wrong payee\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.version").value("version is required"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM cheques WHERE id = ?", String.class, chequeId))
                .isEqualTo("ISSUED");
    }

    @Test
    void shouldRefuseToCancelTwiceOrWithStaleVersion() throws Exception {
        UUID chequeId = createCheque("005200");

        cancel(chequeId, "Wrong payee", 2).andExpect(status().isConflict());
        cancel(chequeId, "Wrong payee", 1).andExpect(status().isOk());
        cancel(chequeId, "Wrong payee", 2).andExpect(status().isConflict());

        assertThat(auditEvents(chequeId)).hasSize(2);
    }

    @Test
    void shouldRejectInvalidUuidPathParameters() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/payment/companies/not-a-uuid/cheques")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authenticated(get(chequesUrl(companyId) + "/not-a-uuid")))
                .andExpect(status().isBadRequest());
    }

    private UUID createCheque(String chequeNumber) throws Exception {
        String body = add(requestJson(Map.of("chequeNumber", chequeNumber)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void assertChequeNumbers(String query, String... chequeNumbers) throws Exception {
        ResultActions result = mockMvc.perform(authenticated(get(chequesUrl(companyId) + query)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(chequeNumbers.length)));
        if (chequeNumbers.length > 0) {
            result.andExpect(jsonPath("$[*].chequeNumber").value(contains((Object[]) chequeNumbers)));
        }
    }

    private ResultActions add(String body) throws Exception {
        return mockMvc.perform(authenticated(post(chequesUrl(companyId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions update(UUID chequeId, String body) throws Exception {
        return mockMvc.perform(authenticated(put(chequesUrl(companyId) + "/" + chequeId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions cancel(UUID chequeId, String reason, int version) throws Exception {
        return mockMvc.perform(authenticated(post(chequesUrl(companyId) + "/" + chequeId + "/cancel"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("reason", reason, "version", version))));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(jwt -> jwt.subject(userId.toString())));
    }

    private static String chequesUrl(UUID companyId) {
        return "/api/v1/payment/companies/" + companyId + "/cheques";
    }

    private String requestJson(Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chequeNumber", "004512");
        body.put("payeeName", "  Kelani Valley Foods  ");
        body.put("category", "SUPPLIER_PAYMENT");
        body.put("amount", "125000.50");
        body.put("issueDate", today.toString());
        body.put("expectedClearingDate", today.plusDays(7).toString());
        body.put("notes", "Invoice INV-2201");
        body.putAll(overrides);
        return JSON.writeValueAsString(body);
    }

    private UUID insertCheque(UUID companyId, String chequeNumber, String status, LocalDate clearingDate) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO cheques (company_id, bank_account_id, cheque_number, payee_name, category, amount,
                                             issue_date, expected_clearing_date, status, created_by)
                        VALUES (?, ?, ?, 'Supplier', 'SUPPLIER_PAYMENT', 1000.00, ?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, chequeNumber, clearingDate.minusDays(30), clearingDate,
                status, UUID.randomUUID());
    }

    private int countCheques(UUID companyId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM cheques WHERE company_id = ?", Integer.class, companyId);
    }

    private List<Map<String, Object>> auditEvents(UUID chequeId) {
        return jdbcTemplate.queryForList("""
                SELECT action, actor_user_id, company_id, reason,
                       before_data ->> 'status' AS before_status, after_data ->> 'status' AS after_status,
                       before_data ->> 'amount' AS before_amount, after_data ->> 'amount' AS after_amount,
                       before_data ->> 'chequeNumber' AS before_number, after_data ->> 'chequeNumber' AS after_number
                FROM payment_audit_events WHERE cheque_id = ? ORDER BY occurred_at, (after_data ->> 'version')::int
                """, chequeId);
    }

    private List<Map<String, Object>> outboxEvents(UUID chequeId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type, published_at, payload::text AS payload
                FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at, (payload ->> 'version')::int
                """, chequeId);
    }
}
