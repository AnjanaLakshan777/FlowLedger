package com.flowledger.payment.controller;

import com.flowledger.payment.support.FakeBankIntegrationServer;
import com.flowledger.payment.support.FakeIdentityServer;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role policy, tenant isolation and fail-closed behaviour for cheques. Membership answers come
 * from FakeIdentityServer over HTTP. Needs the local payment PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChequeAuthorizationIntegrationTest {

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

    private UUID companyId;
    private UUID bankAccountId;
    private LocalDate today;
    private UUID existingCheque;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();
        today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        identityService.reset();
        bankIntegration.reset();
        bankIntegration.currentAccount(companyId, bankAccountId);
        existingCheque = insertCheque(companyId, "000100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER"})
    void shouldLetManagersAddEditAndCancelCheques(String role) throws Exception {
        identityService.grant(companyId, role);

        add("000200").andExpect(status().isCreated());
        edit(existingCheque).andExpect(status().isOk());
        cancel(existingCheque, 2).andExpect(status().isOk());
        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    // The UI matrix gives the accountant the cheque page but not "cheques.manage".
    @Test
    void shouldLetAccountantViewButNotChangeCheques() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        list().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        read(existingCheque).andExpect(status().isOk());
        add("000200").andExpect(status().isForbidden());
        edit(existingCheque).andExpect(status().isForbidden());
        cancel(existingCheque, 1).andExpect(status().isForbidden());

        assertUnchanged();
    }

    @Test
    void shouldDenyCashierEverything() throws Exception {
        identityService.grant(companyId, "CASHIER");

        list().andExpect(status().isForbidden());
        read(existingCheque).andExpect(status().isForbidden());
        add("000200").andExpect(status().isForbidden());
        edit(existingCheque).andExpect(status().isForbidden());
        cancel(existingCheque, 1).andExpect(status().isForbidden());

        assertUnchanged();
    }

    // Confirmed: cheques are financial business data, so a system admin needs a membership.
    @Test
    void shouldGiveSystemAdminWithoutMembershipNoAccess() throws Exception {
        identityService.grantPlatformAdmin(companyId);

        list().andExpect(status().isForbidden());
        add("000200").andExpect(status().isForbidden());
        cancel(existingCheque, 1).andExpect(status().isForbidden());

        assertUnchanged();
    }

    @Test
    void shouldLetSystemAdminManageChequesThroughFinanceControllerMembership() throws Exception {
        identityService.reply(companyId, 200,
                "{\"allowed\":true,\"companyRole\":\"FINANCE_CONTROLLER\",\"platformAdmin\":true}");

        add("000200").andExpect(status().isCreated());
        cancel(existingCheque, 1).andExpect(status().isOk());
    }

    @Test
    void shouldRefuseAnotherCompanysCheques() throws Exception {
        UUID otherCompany = UUID.randomUUID();
        UUID otherCheque = insertCheque(otherCompany, "000300");
        identityService.grant(companyId, "COMPANY_ADMIN");

        mockMvc.perform(get(chequesUrl(otherCompany)).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(post(chequesUrl(otherCompany) + "/" + otherCheque + "/cancel").with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Not ours\", \"version\": 1}"))
                .andExpect(status().isForbidden());
        // Under the user's own company, another company's cheque ID does not resolve.
        cancel(otherCheque, 1).andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM cheques WHERE id = ?", String.class, otherCheque))
                .isEqualTo("ISSUED");
    }

    @Test
    void shouldRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(chequesUrl(companyId))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(chequesUrl(companyId)).contentType(MediaType.APPLICATION_JSON).content(body("000200")))
                .andExpect(status().isUnauthorized());

        assertThat(identityService.lastAuthorization()).isNull();
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500, 503})
    void shouldFailClosedWhenIdentityServiceReturnsError(int identityStatus) throws Exception {
        identityService.reply(companyId, identityStatus, "{}");

        add("000200").andExpect(status().isServiceUnavailable());
        cancel(existingCheque, 1).andExpect(status().isServiceUnavailable());

        assertUnchanged();
    }

    @Test
    void shouldFailClosedWhenIdentityServiceTimesOut() throws Exception {
        identityService.grantAfterDelay(companyId, "COMPANY_ADMIN", Duration.ofSeconds(3));

        add("000200").andExpect(status().isServiceUnavailable());

        assertUnchanged();
    }

    // Authorization is checked before the bank account is looked up.
    @Test
    void shouldNotAskBankIntegrationForUserWithoutAccess() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        add("000200").andExpect(status().isForbidden());

        assertThat(bankIntegration.lastAuthorization()).isNull();
    }

    private void assertUnchanged() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cheques WHERE company_id = ?", Integer.class, companyId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM cheques WHERE id = ?", String.class, existingCheque)).isEqualTo("ISSUED");
    }

    private ResultActions add(String chequeNumber) throws Exception {
        return mockMvc.perform(post(chequesUrl(companyId)).with(user())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(chequeNumber)));
    }

    private ResultActions edit(UUID chequeId) throws Exception {
        return mockMvc.perform(put(chequesUrl(companyId) + "/" + chequeId).with(user())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("000100").replace("\"notes\": null", "\"notes\": \"Edited\", \"version\": 1")));
    }

    private ResultActions cancel(UUID chequeId, int version) throws Exception {
        return mockMvc.perform(post(chequesUrl(companyId) + "/" + chequeId + "/cancel").with(user())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Paid by bank transfer instead\", \"version\": " + version + "}"));
    }

    private ResultActions read(UUID chequeId) throws Exception {
        return mockMvc.perform(get(chequesUrl(companyId) + "/" + chequeId).with(user()));
    }

    private ResultActions list() throws Exception {
        return mockMvc.perform(get(chequesUrl(companyId)).with(user()));
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    private static String chequesUrl(UUID companyId) {
        return "/api/v1/payment/companies/" + companyId + "/cheques";
    }

    private String body(String chequeNumber) {
        return """
                {"chequeNumber": "%s", "payeeName": "Lanka Tiles", "category": "SUPPLIER_PAYMENT",
                 "amount": 45000.00, "issueDate": "%s", "expectedClearingDate": "%s", "notes": null}
                """.formatted(chequeNumber, today, today.plusDays(10));
    }

    private UUID insertCheque(UUID companyId, String chequeNumber) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO cheques (company_id, bank_account_id, cheque_number, payee_name, category, amount,
                                             issue_date, expected_clearing_date, status, created_by)
                        VALUES (?, ?, ?, 'Lanka Tiles', 'SUPPLIER_PAYMENT', 45000.00, ?, ?, 'ISSUED', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, chequeNumber, today, today.plusDays(10), UUID.randomUUID());
    }
}
