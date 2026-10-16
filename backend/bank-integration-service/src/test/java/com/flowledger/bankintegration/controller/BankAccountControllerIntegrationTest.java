package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.support.FakeIdentityServer;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the bank account API for a COMPANY_ADMIN of one company. Membership answers come from
 * FakeIdentityServer over HTTP; role-by-role rules are in BankAccountAuthorizationIntegrationTest.
 * Needs the local bank-integration PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BankAccountControllerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();

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

    @BeforeEach
    void setUp() {
        // Random IDs so the tests never collide with data in the local dev database.
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        otherCompanyId = UUID.randomUUID();

        identityService.reset();
        identityService.grant(companyId, "COMPANY_ADMIN");
    }

    @Test
    void shouldCreateBankAccount() throws Exception {
        String body = mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of("bankName", "  Commercial Bank of Ceylon  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.bankName").value("Commercial Bank of Ceylon"))
                .andExpect(jsonPath("$.accountName").value("ABC Trading (Pvt) Ltd"))
                .andExpect(jsonPath("$.accountType").value("CURRENT"))
                .andExpect(jsonPath("$.accountNumberLast4").value("4821"))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.integrationMode").value("STATEMENT_IMPORT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM bank_accounts WHERE id = ?", id);
        assertThat(row.get("company_id")).isEqualTo(companyId);
        assertThat(row.get("bank_name")).isEqualTo("Commercial Bank of Ceylon");
        assertThat(row.get("account_number_last4")).isEqualTo("4821");
        assertThat(row.get("account_number_encrypted")).isNull();
        assertThat(row.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void shouldCreateBankAccountWithoutAccountNumberDigits() throws Exception {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("accountNumberLast4", null);

        mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(overrides)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumberLast4").doesNotExist());
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("bankName", "   ", "bankName must not be blank"),
                Arguments.of("bankName", "B".repeat(161), "bankName must be at most 160 characters"),
                Arguments.of("accountName", null, "accountName must not be blank"),
                Arguments.of("accountName", "A".repeat(161), "accountName must be at most 160 characters"),
                Arguments.of("accountType", null, "accountType is required"),
                Arguments.of("accountNumberLast4", "123", "accountNumberLast4 must be exactly 4 digits"),
                Arguments.of("accountNumberLast4", "12345", "accountNumberLast4 must be exactly 4 digits"),
                Arguments.of("accountNumberLast4", "12a4", "accountNumberLast4 must be exactly 4 digits"),
                Arguments.of("integrationMode", null, "integrationMode is required")
        );
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidFields")
    void shouldRejectInvalidField(String field, Object value, String message) throws Exception {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(field, value);

        mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(overrides)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors." + field).value(message));

        assertThat(countAccounts(companyId)).isZero();
    }

    @Test
    void shouldRejectUnknownAccountType() throws Exception {
        mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of("accountType", "SAVINGS"))))
                .andExpect(status().isBadRequest());

        assertThat(countAccounts(companyId)).isZero();
    }

    @Test
    void shouldRejectSecondActiveCurrentAccount() throws Exception {
        insertAccount(companyId, "ACTIVE");

        mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This company already has an active current account."));

        assertThat(countAccounts(companyId)).isEqualTo(1);
    }

    @Test
    void shouldAllowNewCurrentAccountWhenPreviousOneIsInactive() throws Exception {
        insertAccount(companyId, "INACTIVE");

        mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldReturnExistingAccount() throws Exception {
        UUID accountId = insertAccount(companyId, "ACTIVE");

        mockMvc.perform(authenticated(get(accountsUrl(companyId) + "/" + accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.bankName").value("Sampath Bank"))
                .andExpect(jsonPath("$.accountNumberLast4").value("0042"));
    }

    @Test
    void shouldReturnNotFoundForUnknownAccount() throws Exception {
        mockMvc.perform(authenticated(get(accountsUrl(companyId) + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Bank account not found."));
    }

    @Test
    void shouldListOnlyTheRequestedCompanysAccounts() throws Exception {
        UUID inactiveId = insertAccount(companyId, "INACTIVE");
        UUID activeId = insertAccount(companyId, "ACTIVE");
        insertAccount(otherCompanyId, "ACTIVE");

        mockMvc.perform(authenticated(get(accountsUrl(companyId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].companyId").value(everyItem(is(companyId.toString()))))
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(inactiveId.toString(), activeId.toString())));
    }

    @Test
    void shouldRefuseAnotherCompanysAccounts() throws Exception {
        UUID otherAccountId = insertAccount(otherCompanyId, "ACTIVE");

        mockMvc.perform(authenticated(get(accountsUrl(otherCompanyId))))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(get(accountsUrl(otherCompanyId) + "/" + otherAccountId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(post(accountsUrl(otherCompanyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isForbidden());

        assertThat(countAccounts(otherCompanyId)).isEqualTo(1);
    }

    @Test
    void shouldNotFindAnotherCompanysAccountThroughOwnCompanyPath() throws Exception {
        UUID otherAccountId = insertAccount(otherCompanyId, "ACTIVE");

        mockMvc.perform(authenticated(get(accountsUrl(companyId) + "/" + otherAccountId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get(accountsUrl(companyId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(accountsUrl(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isUnauthorized());

        assertThat(countAccounts(companyId)).isZero();
        assertThat(identityService.lastAuthorization()).isNull();
    }

    // Editing and deactivating

    @Test
    void shouldEditTheDetailsOfAnActiveAccountAndAuditTheChange() throws Exception {
        UUID id = insertAccount(companyId, "ACTIVE");
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("bankName", " Bank of Ceylon ");
        overrides.put("accountNumberLast4", null);
        overrides.put("integrationMode", "STATEMENT_IMPORT");

        mockMvc.perform(authenticated(patch(accountsUrl(companyId) + "/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(overrides)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("Bank of Ceylon"))
                .andExpect(jsonPath("$.accountNumberLast4").doesNotExist())
                .andExpect(jsonPath("$.integrationMode").value("STATEMENT_IMPORT"))
                .andExpect(jsonPath("$.accountType").value("CURRENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT action, actor_user_id, before_data::text AS before, after_data::text AS after "
                        + "FROM bank_integration_audit_events WHERE entity_id = ?", id);
        assertThat(audit.get("action")).isEqualTo("BANK_ACCOUNT_UPDATED");
        assertThat(audit.get("actor_user_id")).isEqualTo(userId);
        assertThat((String) audit.get("before")).contains("Sampath Bank", "0042");
        assertThat((String) audit.get("after")).contains("Bank of Ceylon");

        // The same details again change nothing and record nothing.
        mockMvc.perform(authenticated(patch(accountsUrl(companyId) + "/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(overrides)))
                .andExpect(status().isOk());
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidEdits() throws Exception {
        UUID id = insertAccount(companyId, "ACTIVE");
        mockMvc.perform(authenticated(patch(accountsUrl(companyId) + "/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of("accountNumberLast4", "12a4"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountNumberLast4").value("accountNumberLast4 must be exactly 4 digits"));
    }

    @Test
    void shouldDeactivateAnAccountKeepingItAndAllowANewCurrentAccount() throws Exception {
        UUID id = insertAccount(companyId, "ACTIVE");

        mockMvc.perform(authenticated(delete(accountsUrl(companyId) + "/" + id)))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bank_accounts WHERE id = ?", String.class, id))
                .isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM bank_integration_audit_events WHERE entity_id = ?", String.class, id))
                .isEqualTo("BANK_ACCOUNT_DEACTIVATED");
        mockMvc.perform(authenticated(post(accountsUrl(companyId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isCreated());
        assertThat(countAccounts(companyId)).isEqualTo(2);
    }

    @Test
    void shouldNotChangeAnInactiveAccount() throws Exception {
        UUID id = insertAccount(companyId, "INACTIVE");

        mockMvc.perform(authenticated(patch(accountsUrl(companyId) + "/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isConflict());
        mockMvc.perform(authenticated(delete(accountsUrl(companyId) + "/" + id)))
                .andExpect(status().isConflict());
        assertThat(auditCount(id)).isZero();
    }

    @Test
    void shouldNotChangeAnotherCompanysAccount() throws Exception {
        UUID foreign = insertAccount(otherCompanyId, "ACTIVE");

        mockMvc.perform(authenticated(patch(accountsUrl(companyId) + "/" + foreign))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticated(delete(accountsUrl(companyId) + "/" + foreign)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bank_accounts WHERE id = ?", String.class, foreign))
                .isEqualTo("ACTIVE");
    }

    @Test
    void shouldLetOnlyCompanyAdminsEditOrDeactivate() throws Exception {
        UUID id = insertAccount(companyId, "ACTIVE");
        identityService.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");

        mockMvc.perform(authenticated(patch(accountsUrl(companyId) + "/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticated(delete(accountsUrl(companyId) + "/" + id)))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bank_accounts WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
    }

    private int auditCount(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_integration_audit_events WHERE entity_id = ?", Integer.class, accountId);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(jwt -> jwt.subject(userId.toString())));
    }

    private static String accountsUrl(UUID companyId) {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-accounts";
    }

    private static String requestJson(Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bankName", "Commercial Bank of Ceylon");
        body.put("accountName", "ABC Trading (Pvt) Ltd");
        body.put("accountType", "CURRENT");
        body.put("accountNumberLast4", "4821");
        body.put("integrationMode", "STATEMENT_IMPORT");
        body.putAll(overrides);
        return JSON.writeValueAsString(body);
    }

    private UUID insertAccount(UUID companyId, String status) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts
                            (company_id, bank_name, account_name, account_type, account_number_last4,
                             integration_mode, status)
                        VALUES (?, 'Sampath Bank', 'ABC Trading (Pvt) Ltd', 'CURRENT', '0042', 'MANUAL', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, status);
    }

    private int countAccounts(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_accounts WHERE company_id = ?", Integer.class, companyId);
    }
}
