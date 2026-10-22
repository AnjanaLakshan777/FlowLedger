package com.flowledger.bankintegration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal read API used by forecast-service's daily scheduled forecast: the internal API key
 * only, never a user token. Needs the local bank-integration database; each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalBankDataIntegrationTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${flowledger.security.internal-api.secret}")
    private String internalKey;

    private UUID companyId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountId = jdbcTemplate.queryForObject("""
                INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                VALUES (?, 'Bank of Ceylon', 'Current', 'CURRENT', 'STATEMENT_IMPORT', 'ACTIVE')
                RETURNING id
                """, UUID.class, companyId);
    }

    @Test
    void shouldServeTheForecastInputsWithTheInternalKey() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO bank_balance_snapshots (company_id, bank_account_id, balance_amount, balance_as_of, source_type)
                VALUES (?, ?, 435343.48, TIMESTAMPTZ '2026-09-30 18:29:59Z', 'MANUAL')
                """, companyId, accountId);

        mockMvc.perform(internal(base() + "/bank-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(accountId.toString()));
        mockMvc.perform(internal(base() + "/bank-accounts/" + accountId + "/balance-snapshots/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(435343.48));
        mockMvc.perform(internal(base() + "/statement-imports")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(internal(base() + "/bank-transactions?bankAccountId=" + accountId + "&from=2026-09-01&to=2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldAnswerAMissingBalanceWith404() throws Exception {
        mockMvc.perform(internal(base() + "/bank-accounts/" + accountId + "/balance-snapshots/latest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRefuseWithoutTheKeyOrWithAUserToken() throws Exception {
        mockMvc.perform(get(base() + "/bank-accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(base() + "/bank-accounts").header(KEY_HEADER, "wrong-key")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(base() + "/bank-accounts").with(jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder internal(String path) {
        return get(path).header(KEY_HEADER, internalKey);
    }

    private String base() {
        return "/internal/v1/bank-integration/companies/" + companyId;
    }
}
