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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manual bank entry for MANUAL accounts, and the rule that each account takes bank data only the
 * way its connection says. Needs the local bank-integration PostgreSQL database. Each test is
 * rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManualEntryIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Colombo"));

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
    private UUID manualAccount;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        identityService.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        manualAccount = insertAccount("MANUAL");
    }

    // Transactions

    @Test
    void shouldRecordAClassifiedManualTransactionWithAudit() throws Exception {
        String body = addTransaction(manualAccount, transaction(TODAY, "CREDIT", "125000.50", "CASH_SALES_DEPOSIT", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.direction").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(125000.50))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.description").value("Cash deposit"))
                .andExpect(jsonPath("$.category").value("CASH_SALES_DEPOSIT"))
                .andExpect(jsonPath("$.classificationMethod").value("MANUAL"))
                .andExpect(jsonPath("$.classifiedBy").value(userId.toString()))
                .andExpect(jsonPath("$.entrySource").value("MANUAL"))
                .andExpect(jsonPath("$.runningBalance").doesNotExist())
                .andExpect(jsonPath("$.sourceImportBatchId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(jdbcTemplate.queryForObject("SELECT entered_by FROM bank_transactions WHERE id = ?", UUID.class, id))
                .isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM bank_integration_audit_events WHERE entity_id = ?", String.class, id))
                .isEqualTo("BANK_TRANSACTION_ENTERED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM outbox_events WHERE aggregate_id = ?", String.class, id))
                .isEqualTo("BankTransactionEntered");

        // It is listed with the account's other transactions.
        mockMvc.perform(authenticated(get(companyUrl() + "/bank-transactions").param("bankAccountId", manualAccount.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void shouldLeaveATransactionWithoutCategoryUnclassified() throws Exception {
        addTransaction(manualAccount, transaction(TODAY, "DEBIT", "5000", null, "000123"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("UNCLASSIFIED"))
                .andExpect(jsonPath("$.classificationMethod").value("UNKNOWN"))
                .andExpect(jsonPath("$.classifiedBy").doesNotExist())
                .andExpect(jsonPath("$.chequeNumber").value("000123"));
    }

    @Test
    void shouldRejectInvalidTransactions() throws Exception {
        addTransaction(manualAccount, transaction(TODAY.plusDays(1), "CREDIT", "10", null, null))
                .andExpect(status().isBadRequest());
        addTransaction(manualAccount, transaction(TODAY, "DEBIT", "10", "CASH_SALES_DEPOSIT", null))
                .andExpect(status().isBadRequest());
        addTransaction(manualAccount, transaction(TODAY, "CREDIT", "0", null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
        addTransaction(manualAccount, transaction(TODAY, "CREDIT", "10.123", null, null))
                .andExpect(status().isBadRequest());
        addTransaction(manualAccount, transaction(TODAY, "DEBIT", "10", null, "12a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.chequeNumber").exists());
        assertThat(transactionCount()).isZero();
    }

    @Test
    void shouldDeleteOnlyManualTransactions() throws Exception {
        String body = addTransaction(manualAccount, transaction(TODAY, "CREDIT", "100", null, null))
                .andReturn().getResponse().getContentAsString();
        UUID manual = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(authenticated(delete(manualTransactionsUrl(manualAccount) + "/" + manual)))
                .andExpect(status().isNoContent());
        assertThat(transactionCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_integration_audit_events WHERE entity_id = ? AND action = 'BANK_TRANSACTION_DELETED'",
                Integer.class, manual)).isEqualTo(1);

        UUID imported = jdbcTemplate.queryForObject("""
                        INSERT INTO bank_transactions
                            (company_id, bank_account_id, deduplication_fingerprint, transaction_date, direction, amount)
                        VALUES (?, ?, repeat('a', 64), ?, 'CREDIT', 50)
                        RETURNING id
                        """, UUID.class, companyId, manualAccount, TODAY);
        mockMvc.perform(authenticated(delete(manualTransactionsUrl(manualAccount) + "/" + imported)))
                .andExpect(status().isBadRequest());
        assertThat(transactionCount()).isEqualTo(1);
    }

    // Balances

    @Test
    void shouldRecordAManualBalanceAsTheLatestBalance() throws Exception {
        String asOf = TODAY.minusDays(1) + "T17:30:00+05:30";
        recordBalance(manualAccount, "{\"balanceAmount\": -2500.75, \"balanceAsOf\": \"" + asOf + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAmount").value(-2500.75))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"));

        mockMvc.perform(authenticated(get(companyUrl() + "/bank-accounts/" + manualAccount + "/balance-snapshots/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(-2500.75))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"));

        // The same balance at the same time again is refused.
        recordBalance(manualAccount, "{\"balanceAmount\": -2500.75, \"balanceAsOf\": \"" + asOf + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRejectAFutureBalance() throws Exception {
        recordBalance(manualAccount, "{\"balanceAmount\": 10, \"balanceAsOf\": \"" + TODAY.plusDays(2) + "T09:00:00+05:30\"}")
                .andExpect(status().isBadRequest());
    }

    // Connection rules and access

    @Test
    void shouldTakeBankDataOnlyTheWayTheConnectionSays() throws Exception {
        UUID statementAccount = insertAccount("STATEMENT_IMPORT", UUID.randomUUID());
        identityService.grant(companyIdOf(statementAccount), "FINANCE_CONTROLLER");

        mockMvc.perform(authenticated(post("/api/v1/bank-integration/companies/" + companyIdOf(statementAccount)
                                + "/bank-accounts/" + statementAccount + "/manual-transactions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transaction(TODAY, "CREDIT", "10", null, null)))
                .andExpect(status().isConflict());

        mockMvc.perform(authenticated(multipart(companyUrl() + "/bank-accounts/" + manualAccount + "/statement-imports")
                        .file(new MockMultipartFile("file", "statement.csv", "text/csv", StatementImportIntegrationTest.sample()))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?", Integer.class, companyId)).isZero();
    }

    @Test
    void shouldRefuseInactiveAccountsAndOtherRoles() throws Exception {
        jdbcTemplate.update("UPDATE bank_accounts SET status = 'INACTIVE' WHERE id = ?", manualAccount);
        addTransaction(manualAccount, transaction(TODAY, "CREDIT", "10", null, null)).andExpect(status().isConflict());

        UUID active = insertAccount("MANUAL");
        identityService.reset();
        identityService.grant(companyId, "ACCOUNTANT");
        addTransaction(active, transaction(TODAY, "CREDIT", "10", null, null)).andExpect(status().isForbidden());
        recordBalance(active, "{\"balanceAmount\": 10, \"balanceAsOf\": \"" + TODAY + "T09:00:00+05:30\"}")
                .andExpect(status().isForbidden());
        assertThat(transactionCount()).isZero();
    }

    private ResultActions addTransaction(UUID accountId, String body) throws Exception {
        return mockMvc.perform(authenticated(post(manualTransactionsUrl(accountId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions recordBalance(UUID accountId, String body) throws Exception {
        return mockMvc.perform(authenticated(post(companyUrl() + "/bank-accounts/" + accountId + "/manual-balances"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String transaction(LocalDate date, String direction, String amount, String category, String cheque) {
        return "{\"transactionDate\": \"" + date + "\", \"direction\": \"" + direction + "\", \"amount\": " + amount
                + ", \"description\": \"Cash deposit\""
                + (category == null ? "" : ", \"category\": \"" + category + "\"")
                + (cheque == null ? "" : ", \"chequeNumber\": \"" + cheque + "\"") + "}";
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B authenticated(B request) {
        return request.with(jwt().jwt(jwt -> jwt.subject(userId.toString())));
    }

    private String companyUrl() {
        return "/api/v1/bank-integration/companies/" + companyId;
    }

    private String manualTransactionsUrl(UUID accountId) {
        return companyUrl() + "/bank-accounts/" + accountId + "/manual-transactions";
    }

    private int transactionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_transactions WHERE company_id = ?", Integer.class, companyId);
    }

    private UUID companyIdOf(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT company_id FROM bank_accounts WHERE id = ?", UUID.class, accountId);
    }

    private UUID insertAccount(String integrationMode) {
        return insertAccount(integrationMode, companyId);
    }

    // A company has at most one active current account, so a second active one needs the first inactive
    // or another company.
    private UUID insertAccount(String integrationMode, UUID company) {
        jdbcTemplate.update("UPDATE bank_accounts SET status = 'INACTIVE' WHERE company_id = ? AND status = 'ACTIVE'", company);
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts
                            (company_id, bank_name, branch_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Bank of Ceylon', 'Colombo 03', 'ABC Private(Ltd)', 'CURRENT', ?, 'ACTIVE')
                        RETURNING id
                        """,
                UUID.class, company, integrationMode);
    }
}
