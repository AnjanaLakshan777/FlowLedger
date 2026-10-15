package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.support.FakeIdentityServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The latest confirmed balance of an account, as read by forecast-service. Needs the local
 * bank-integration PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BankBalanceIntegrationTest {

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
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"})
    void shouldReturnTheNewestBalance(String role) throws Exception {
        identityService.grant(companyId, role);
        insertSnapshot("8373.55", "2026-09-30 23:59:59+05:30", "2026-10-01 09:00:00+05:30");
        insertSnapshot("-1500.25", "2026-10-14 23:59:59+05:30", "2026-10-15 09:00:00+05:30");
        insertSnapshot("9100.00", "2026-10-07 23:59:59+05:30", "2026-10-15 10:00:00+05:30");

        latest(companyId, accountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.balanceAmount").value(-1500.25))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.balanceAsOf").value("2026-10-14T18:29:59Z"))
                .andExpect(jsonPath("$.sourceType").value("STATEMENT_IMPORT"));
    }

    // Two statements ending on the same day: the balance recorded last is the newer information.
    @Test
    void shouldPreferTheLaterRecordedBalanceForTheSameTime() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        insertSnapshot("100.00", "2026-10-14 23:59:59+05:30", "2026-10-15 09:00:00+05:30");
        insertSnapshot("250.00", "2026-10-14 23:59:59+05:30", "2026-10-15 11:00:00+05:30");

        latest(companyId, accountId).andExpect(status().isOk()).andExpect(jsonPath("$.balanceAmount").value(250.0));
    }

    @Test
    void shouldReportAMissingBalanceAsNotFoundInsteadOfZero() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");

        latest(companyId, accountId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No confirmed balance has been recorded for this bank account."));
    }

    @Test
    void shouldNotRevealAnotherCompanysBalance() throws Exception {
        insertSnapshot("100.00", "2026-10-14 23:59:59+05:30", "2026-10-15 09:00:00+05:30");
        UUID otherCompany = UUID.randomUUID();
        identityService.grant(otherCompany, "FINANCE_CONTROLLER");

        latest(otherCompany, accountId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Bank account not found."));
        latest(companyId, accountId).andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyCashierAndSystemAdminWithoutMembership() throws Exception {
        insertSnapshot("100.00", "2026-10-14 23:59:59+05:30", "2026-10-15 09:00:00+05:30");

        identityService.grant(companyId, "CASHIER");
        latest(companyId, accountId).andExpect(status().isForbidden());

        identityService.grantPlatformAdmin(companyId);
        latest(companyId, accountId).andExpect(status().isForbidden());
    }

    private ResultActions latest(UUID company, UUID bankAccountId) throws Exception {
        return mockMvc.perform(get("/api/v1/bank-integration/companies/" + company + "/bank-accounts/"
                + bankAccountId + "/balance-snapshots/latest")
                .with(jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()))));
    }

    private void insertSnapshot(String amount, String asOf, String createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO bank_balance_snapshots (company_id, bank_account_id, balance_amount, balance_as_of,
                                                            source_type, created_at)
                        VALUES (?, ?, ?::numeric, ?::timestamptz, 'STATEMENT_IMPORT', ?::timestamptz)
                        """,
                companyId, accountId, amount, asOf, createdAt);
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
