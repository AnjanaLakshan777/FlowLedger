package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.config.IdentityServiceProperties;
import com.flowledger.bankintegration.support.FakeIdentityServer;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role policy and fail-closed behaviour of company authorization. Membership answers come
 * from FakeIdentityServer over HTTP. Needs the local bank-integration PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BankAccountAuthorizationIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final String USER_TOKEN = "user-access-token";
    private static final String CREATE_REQUEST = """
            {"bankName": "Commercial Bank of Ceylon", "accountName": "ABC Trading (Pvt) Ltd",
             "accountType": "CURRENT", "accountNumberLast4": "4821", "integrationMode": "MANUAL"}
            """;

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

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        identityService.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER"})
    void shouldLetAdminAndFinanceControllerCreateAndReadAccounts(String role) throws Exception {
        identityService.grant(companyId, role);

        String accountId = JsonPath.read(
                create().andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        read(accountId).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(accountId));
        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldLetAccountantViewButNotCreateAccounts() throws Exception {
        UUID accountId = insertAccount();
        identityService.grant(companyId, "ACCOUNTANT");

        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        read(accountId.toString()).andExpect(status().isOk());
        create().andExpect(status().isForbidden());

        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void shouldDenyCashier() throws Exception {
        UUID accountId = insertAccount();
        identityService.grant(companyId, "CASHIER");

        list().andExpect(status().isForbidden());
        read(accountId.toString()).andExpect(status().isForbidden());
        create().andExpect(status().isForbidden());

        assertThat(countAccounts()).isEqualTo(1);
    }

    // identity-service answers "not allowed" for INVITED and SUSPENDED memberships, inactive
    // accounts and inactive companies (tested in identity-service). Here: the bank side honours it.
    @Test
    void shouldDenyWhenIdentityServiceDoesNotAllowMembership() throws Exception {
        UUID accountId = insertAccount();

        list().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You do not have access to this company."));
        read(accountId.toString()).andExpect(status().isForbidden());
        create().andExpect(status().isForbidden());

        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void shouldDenyWhenAllowedAnswerHasNoRole() throws Exception {
        identityService.reply(companyId, 200, "{\"allowed\":true,\"companyRole\":null}");

        list().andExpect(status().isForbidden());
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

        list().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Company access could not be verified. Please try again later."));
        create().andExpect(status().isServiceUnavailable());

        assertThat(countAccounts()).isZero();
    }

    @Test
    void shouldFailClosedWhenIdentityServiceResponseIsUnreadable() throws Exception {
        identityService.reply(companyId, 200, "{not json");

        list().andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldFailClosedWhenIdentityServiceReturnsUnknownRole() throws Exception {
        identityService.reply(companyId, 200, "{\"allowed\":true,\"companyRole\":\"SUPER_ADMIN\"}");

        list().andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldFailClosedWhenIdentityServiceTimesOut() throws Exception {
        // Would grant access, but answers after the 1s read timeout configured for tests.
        identityService.grantAfterDelay(companyId, "COMPANY_ADMIN", Duration.ofSeconds(3));

        create().andExpect(status().isServiceUnavailable());

        assertThat(countAccounts()).isZero();
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post(accountsUrl())
                .with(userToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_REQUEST));
    }

    private ResultActions read(String accountId) throws Exception {
        return mockMvc.perform(get(accountsUrl() + "/" + accountId).with(userToken()));
    }

    private ResultActions list() throws Exception {
        return mockMvc.perform(get(accountsUrl()).with(userToken()));
    }

    private static RequestPostProcessor userToken() {
        return jwt().jwt(jwt -> jwt.tokenValue(USER_TOKEN).subject(UUID.randomUUID().toString()));
    }

    private String accountsUrl() {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-accounts";
    }

    private UUID insertAccount() {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts
                            (company_id, bank_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Sampath Bank', 'ABC Trading (Pvt) Ltd', 'CURRENT', 'MANUAL', 'ACTIVE')
                        RETURNING id
                        """,
                UUID.class, companyId);
    }

    private int countAccounts() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_accounts WHERE company_id = ?", Integer.class, companyId);
    }
}
