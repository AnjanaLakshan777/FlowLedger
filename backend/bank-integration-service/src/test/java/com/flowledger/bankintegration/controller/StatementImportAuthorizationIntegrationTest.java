package com.flowledger.bankintegration.controller;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may import statements, view transactions and classify them, and that one company never
 * reaches another company's statements. Needs the local bank-integration PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatementImportAuthorizationIntegrationTest {

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
    private UUID accountId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountId = insertAccount(companyId);
        identityService.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER"})
    void shouldLetAdminAndFinanceControllerImportAndClassify(String role) throws Exception {
        identityService.grant(companyId, role);

        upload(companyId, accountId).andExpect(status().isCreated());
        classify(companyId, transactionId(companyId)).andExpect(status().isOk());
    }

    @Test
    void shouldLetAccountantViewButNotImportOrClassify() throws Exception {
        String batchId = importAsFinanceController();
        UUID transactionId = transactionId(companyId);
        identityService.grant(companyId, "ACCOUNTANT");

        mockMvc.perform(get(importsUrl(companyId)).with(user())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get(importsUrl(companyId) + "/" + batchId).with(user())).andExpect(status().isOk());
        mockMvc.perform(get(transactionsUrl(companyId)).with(user())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(55)));
        mockMvc.perform(get(transactionsUrl(companyId) + "/" + transactionId).with(user())).andExpect(status().isOk());

        upload(companyId, accountId).andExpect(status().isForbidden());
        classify(companyId, transactionId).andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND category <> 'UNCLASSIFIED'"))
                .isZero();
    }

    @Test
    void shouldDenyCashierEverything() throws Exception {
        String batchId = importAsFinanceController();
        UUID transactionId = transactionId(companyId);
        identityService.grant(companyId, "CASHIER");

        upload(companyId, accountId).andExpect(status().isForbidden());
        mockMvc.perform(get(importsUrl(companyId)).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(importsUrl(companyId) + "/" + batchId).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(transactionsUrl(companyId)).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(transactionsUrl(companyId) + "/" + transactionId).with(user()))
                .andExpect(status().isForbidden());
        classify(companyId, transactionId).andExpect(status().isForbidden());
    }

    // Unlike bank account configuration, statement data is business data: no platform bypass.
    @Test
    void shouldDenySystemAdminWithoutMembership() throws Exception {
        importAsFinanceController();
        UUID transactionId = transactionId(companyId);
        identityService.grantPlatformAdmin(companyId);

        upload(companyId, accountId).andExpect(status().isForbidden());
        mockMvc.perform(get(importsUrl(companyId)).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(transactionsUrl(companyId)).with(user())).andExpect(status().isForbidden());
        classify(companyId, transactionId).andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isEqualTo(1);
    }

    @Test
    void shouldDenyNonMembersBeforeLookingAtTheFile() throws Exception {
        mockMvc.perform(multipart(accountImportsUrl(companyId, accountId))
                        .file(new MockMultipartFile("file", "statement.exe", "image/png", new byte[0]))
                        .with(user()))
                .andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isZero();
    }

    @Test
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(multipart(accountImportsUrl(companyId, accountId)).file(sampleFile()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(transactionsUrl(companyId))).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldFailClosedWhenIdentityServiceIsUnavailable() throws Exception {
        identityService.reply(companyId, 500, "{}");

        upload(companyId, accountId).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get(transactionsUrl(companyId)).with(user())).andExpect(status().isServiceUnavailable());

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isZero();
    }

    // A finance controller of another company reaches nothing of this company through their own.
    @Test
    void shouldKeepStatementDataWithinTheCompany() throws Exception {
        String batchId = importAsFinanceController();
        UUID transactionId = transactionId(companyId);
        UUID otherCompany = UUID.randomUUID();
        identityService.grant(otherCompany, "FINANCE_CONTROLLER");

        upload(otherCompany, accountId).andExpect(status().isNotFound());
        mockMvc.perform(get(importsUrl(otherCompany)).with(user())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(importsUrl(otherCompany) + "/" + batchId).with(user())).andExpect(status().isNotFound());
        mockMvc.perform(get(transactionsUrl(otherCompany)).with(user())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(transactionsUrl(otherCompany) + "?bankAccountId=" + accountId).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get(transactionsUrl(otherCompany) + "/" + transactionId).with(user()))
                .andExpect(status().isNotFound());
        classify(otherCompany, transactionId).andExpect(status().isNotFound());

        // And without a membership in this company, its URLs are simply forbidden.
        identityService.reset();
        identityService.grant(otherCompany, "FINANCE_CONTROLLER");
        mockMvc.perform(get(transactionsUrl(companyId)).with(user())).andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND category <> 'UNCLASSIFIED'"))
                .isZero();
    }

    private String importAsFinanceController() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String body = upload(companyId, accountId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions upload(UUID company, UUID bankAccountId) throws Exception {
        return mockMvc.perform(multipart(accountImportsUrl(company, bankAccountId)).file(sampleFile()).with(user()));
    }

    private ResultActions classify(UUID company, UUID transactionId) throws Exception {
        return mockMvc.perform(put(transactionsUrl(company) + "/" + transactionId + "/classification")
                .with(user())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\": \"OTHER\"}"));
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    private static MockMultipartFile sampleFile() throws Exception {
        return new MockMultipartFile("file", "statement.csv", "text/csv", StatementImportIntegrationTest.sample());
    }

    private static String accountImportsUrl(UUID company, UUID bankAccountId) {
        return "/api/v1/bank-integration/companies/" + company + "/bank-accounts/" + bankAccountId + "/statement-imports";
    }

    private static String importsUrl(UUID company) {
        return "/api/v1/bank-integration/companies/" + company + "/statement-imports";
    }

    private static String transactionsUrl(UUID company) {
        return "/api/v1/bank-integration/companies/" + company + "/bank-transactions";
    }

    // A credit, so every category the test classifies with fits it.
    private UUID transactionId(UUID company) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM bank_transactions WHERE company_id = ? AND direction = 'CREDIT'
                ORDER BY entry_sequence LIMIT 1
                """, UUID.class, company);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, companyId);
    }

    private UUID insertAccount(UUID company) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Commercial Bank of Ceylon', 'Current', 'CURRENT', 'STATEMENT_IMPORT', 'ACTIVE')
                        RETURNING id
                        """,
                UUID.class, company);
    }
}
