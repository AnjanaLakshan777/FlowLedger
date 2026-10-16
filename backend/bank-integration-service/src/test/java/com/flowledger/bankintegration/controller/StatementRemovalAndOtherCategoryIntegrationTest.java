package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Removing an imported statement, and the description an OTHER transaction must carry. Needs the
 * local bank-integration PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatementRemovalAndOtherCategoryIntegrationTest {

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
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        accountId = insertAccount("STATEMENT_IMPORT");
        identityService.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
    }

    // Removing a statement

    @Test
    void shouldRemoveAnImportWithItsTransactionsAndBalance() throws Exception {
        UUID batch = upload();
        assertThat(count("bank_transactions")).isEqualTo(55);
        assertThat(count("bank_balance_snapshots")).isEqualTo(1);

        remove(batch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.removedBy").value(userId.toString()))
                .andExpect(jsonPath("$.removedAt").isNotEmpty())
                .andExpect(jsonPath("$.importedRowCount").value(55));

        assertThat(count("bank_transactions")).isZero();
        assertThat(count("bank_balance_snapshots")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT after_data ->> 'deletedTransactionCount' FROM bank_integration_audit_events "
                        + "WHERE entity_id = ? AND action = 'BANK_STATEMENT_REMOVED'", String.class, batch)).isEqualTo("55");
        mockMvc.perform(authenticated(get(companyUrl() + "/bank-accounts/" + accountId + "/balance-snapshots/latest")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticated(get(companyUrl() + "/statement-imports")))
                .andExpect(jsonPath("$[0].status").value("REMOVED"));

        remove(batch).andExpect(status().isConflict());

        // The same statement can be imported again afterwards, in full.
        mockMvc.perform(authenticated(multipart(importUrl()).file(sampleFile())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedRowCount").value(55))
                .andExpect(jsonPath("$.duplicateRowCount").value(0));
    }

    @Test
    void shouldKeepRowsThatBelongToAnEarlierImport() throws Exception {
        upload();
        UUID repeat = upload(); // every row already imported
        remove(repeat).andExpect(status().isOk());
        assertThat(count("bank_transactions")).isEqualTo(55);
        assertThat(count("bank_balance_snapshots")).isEqualTo(1);
    }

    @Test
    void shouldLetOnlyImportingRolesRemoveAndOnlyInTheirCompany() throws Exception {
        UUID batch = upload();
        identityService.reset();
        identityService.grant(companyId, "ACCOUNTANT");
        remove(batch).andExpect(status().isForbidden());

        UUID otherCompany = UUID.randomUUID();
        identityService.grant(otherCompany, "FINANCE_CONTROLLER");
        mockMvc.perform(authenticated(delete("/api/v1/bank-integration/companies/" + otherCompany + "/statement-imports/" + batch)))
                .andExpect(status().isNotFound());
        assertThat(count("bank_transactions")).isEqualTo(55);
    }

    // "Other" with a description

    @Test
    void shouldRequireADescriptionForOtherAndClearItForOtherCategories() throws Exception {
        upload();
        UUID credit = jdbcTemplate.queryForObject(
                "SELECT id FROM bank_transactions WHERE company_id = ? AND direction = 'CREDIT' LIMIT 1", UUID.class, companyId);

        classify(credit, "{\"category\": \"OTHER\"}").andExpect(status().isBadRequest());
        classify(credit, "{\"category\": \"OTHER\", \"otherDescription\": \"  Loan from director \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("OTHER"))
                .andExpect(jsonPath("$.otherDescription").value("Loan from director"));
        // A new description alone is a change, and is audited.
        classify(credit, "{\"category\": \"OTHER\", \"otherDescription\": \"Insurance refund\"}")
                .andExpect(jsonPath("$.otherDescription").value("Insurance refund"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_integration_audit_events WHERE entity_id = ?", Integer.class, credit)).isEqualTo(2);

        classify(credit, "{\"category\": \"CASH_SALES_DEPOSIT\", \"otherDescription\": \"ignored\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherDescription").doesNotExist());
    }

    @Test
    void shouldRequireADescriptionForAManualOtherTransaction() throws Exception {
        jdbcTemplate.update("UPDATE bank_accounts SET integration_mode = 'MANUAL' WHERE id = ?", accountId);
        String today = LocalDate.now(ZoneId.of("Asia/Colombo")).toString();
        String base = "{\"transactionDate\": \"" + today + "\", \"direction\": \"DEBIT\", \"amount\": 2500, "
                + "\"description\": \"Electricity bill\", \"category\": \"OTHER\"";

        manual(base + "}").andExpect(status().isBadRequest());
        manual(base + ", \"otherDescription\": \"Electricity\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("OTHER"))
                .andExpect(jsonPath("$.otherDescription").value("Electricity"));
    }

    private UUID upload() throws Exception {
        String body = mockMvc.perform(authenticated(multipart(importUrl()).file(sampleFile())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions remove(UUID batchId) throws Exception {
        return mockMvc.perform(authenticated(delete(companyUrl() + "/statement-imports/" + batchId)));
    }

    private ResultActions classify(UUID transactionId, String body) throws Exception {
        return mockMvc.perform(authenticated(put(companyUrl() + "/bank-transactions/" + transactionId + "/classification"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions manual(String body) throws Exception {
        return mockMvc.perform(authenticated(post(companyUrl() + "/bank-accounts/" + accountId + "/manual-transactions"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B authenticated(B request) {
        return request.with(jwt().jwt(jwt -> jwt.subject(userId.toString())));
    }

    private String companyUrl() {
        return "/api/v1/bank-integration/companies/" + companyId;
    }

    private String importUrl() {
        return companyUrl() + "/bank-accounts/" + accountId + "/statement-imports";
    }

    private static MockMultipartFile sampleFile() throws Exception {
        return new MockMultipartFile("file", "statement.csv", "text/csv", StatementImportIntegrationTest.sample());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE company_id = ?", Integer.class, companyId);
    }

    private UUID insertAccount(String integrationMode) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts
                            (company_id, bank_name, branch_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Bank of Ceylon', 'Colombo 03', 'ABC Private(Ltd)', 'CURRENT', ?, 'ACTIVE')
                        RETURNING id
                        """,
                UUID.class, companyId, integrationMode);
    }
}
