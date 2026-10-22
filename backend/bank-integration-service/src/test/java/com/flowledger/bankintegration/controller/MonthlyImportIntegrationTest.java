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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Monthly import from FlowLedger's template. Months are relative to the real current month in
 * Asia/Colombo, since only ended months can be imported. Membership answers come from
 * FakeIdentityServer. Needs the local bank-integration database; each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MonthlyImportIntegrationTest {

    private static final String HEADER =
            "Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID,Category,Other Description\n";
    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final YearMonth THIS_MONTH = YearMonth.now(ZoneId.of("Asia/Colombo"));
    private static final YearMonth LAST_MONTH = THIS_MONTH.minusMonths(1);
    private static final YearMonth MONTH_BEFORE = THIS_MONTH.minusMonths(2);

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
        accountId = jdbcTemplate.queryForObject("""
                INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                VALUES (?, 'Bank of Ceylon', 'Current', 'CURRENT', 'STATEMENT_IMPORT', 'ACTIVE')
                RETURNING id
                """, UUID.class, companyId);
        identityService.reset();
        identityService.grant(companyId, "ACCOUNTANT");
    }

    @Test
    void shouldImportAMonthWithItsCategoriesAndCoverTheWholeMonth() throws Exception {
        String body = upload(LAST_MONTH, month(LAST_MONTH, "1000.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.fileFormat").value("FLOWLEDGER_MONTHLY_CSV"))
                .andExpect(jsonPath("$.periodStart").value(LAST_MONTH.atDay(1).toString()))
                .andExpect(jsonPath("$.periodEnd").value(LAST_MONTH.atEndOfMonth().toString()))
                .andExpect(jsonPath("$.importedRowCount").value(4))
                .andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(JsonPath.read(body, "$.id"));

        Map<String, Object> cheque = jdbcTemplate.queryForMap("""
                SELECT category, classification_method, classified_by, cheque_number FROM bank_transactions
                WHERE company_id = ? AND category = 'CHEQUE_PAYMENT'
                """, companyId);
        assertThat(cheque).containsEntry("classification_method", "MANUAL").containsEntry("classified_by", userId)
                .containsEntry("cheque_number", "000605001");
        assertThat(jdbcTemplate.queryForObject("SELECT other_description FROM bank_transactions WHERE company_id = ? AND category = 'OTHER'",
                String.class, companyId)).isEqualTo("Bank charges");
        // The closing balance applies at the end of the month's last day.
        assertThat(jdbcTemplate.queryForMap("""
                SELECT balance_amount, (balance_as_of AT TIME ZONE 'Asia/Colombo')::date AS as_of FROM bank_balance_snapshots
                WHERE source_import_batch_id = ?
                """, batchId)).containsEntry("as_of", java.sql.Date.valueOf(LAST_MONTH.atEndOfMonth()))
                .satisfies(row -> assertThat((java.math.BigDecimal) row.get("balance_amount")).isEqualByComparingTo("2000.00"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE event_type = 'BankTransactionImported'
                  AND payload ->> 'companyId' = ? AND payload ->> 'category' = 'CHEQUE_PAYMENT'
                """, Integer.class, companyId.toString())).isEqualTo(1);
    }

    @Test
    void shouldRefuseTheCurrentMonthAndBadMonths() throws Exception {
        upload(THIS_MONTH, month(THIS_MONTH, "0.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("has not ended yet")));
        upload(null, month(LAST_MONTH, "0.00")).andExpect(status().isBadRequest());
        mockMvc.perform(multipart(url()).file(file(month(LAST_MONTH, "0.00"))).param("month", "2026/09").with(user()))
                .andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test
    void shouldRejectAFileWithRowsOutsideTheMonthAndKeepItAsFailed() throws Exception {
        upload(MONTH_BEFORE, month(LAST_MONTH, "0.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("Date"));
        assertThat(count()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bank_statement_import_batches WHERE company_id = ?",
                String.class, companyId)).isEqualTo("FAILED");
    }

    @Test
    void shouldImportEachMonthOnlyOnceUntilRemoved() throws Exception {
        String body = upload(LAST_MONTH, month(LAST_MONTH, "1000.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        upload(LAST_MONTH, month(LAST_MONTH, "1000.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already imported")));
        assertThat(count()).isEqualTo(4);

        mockMvc.perform(delete("/api/v1/bank-integration/companies/" + companyId + "/statement-imports/" + JsonPath.read(body, "$.id"))
                .with(user())).andExpect(status().isOk());
        upload(LAST_MONTH, month(LAST_MONTH, "1000.00")).andExpect(status().isCreated());
        assertThat(count()).isEqualTo(4);
    }

    @Test
    void shouldContinueFromLastMonthsClosingBalance() throws Exception {
        // The month before closes at 2,000.00.
        upload(MONTH_BEFORE, month(MONTH_BEFORE, "1000.00")).andExpect(status().isCreated());

        upload(LAST_MONTH, month(LAST_MONTH, "1500.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message", containsString("last month's import closed with LKR 2000.00")));
        upload(LAST_MONTH, month(LAST_MONTH, "2000.00")).andExpect(status().isCreated());
        assertThat(count()).isEqualTo(8);
    }

    @Test
    void shouldKeepTheBanksStatementOutOfAMonthEnteredFromTheTemplate() throws Exception {
        upload(LAST_MONTH, month(LAST_MONTH, "1000.00")).andExpect(status().isCreated());
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String day = LAST_MONTH.atDay(10).format(java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"));
        String statement = "Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID\n"
                + day + ",DEPOSIT,Credit,10.00,,1010.00,0,631\n";

        mockMvc.perform(multipart("/api/v1/bank-integration/companies/" + companyId + "/bank-accounts/" + accountId + "/statement-imports")
                        .file(new MockMultipartFile("file", "statement.csv", "text/csv", statement.getBytes(StandardCharsets.UTF_8)))
                        .with(user()))
                .andExpect(status().isConflict());
        assertThat(count()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"})
    void shouldLetTheAccountantAndAboveImport(String role) throws Exception {
        identityService.reset();
        identityService.grant(companyId, role);
        upload(LAST_MONTH, month(LAST_MONTH, "1000.00")).andExpect(status().isCreated());
    }

    @Test
    void shouldKeepCashiersAndOtherCompaniesOut() throws Exception {
        identityService.reset();
        identityService.grant(companyId, "CASHIER");
        upload(LAST_MONTH, month(LAST_MONTH, "1000.00")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/bank-integration/companies/" + companyId + "/statement-imports").with(user()))
                .andExpect(status().isForbidden());
        assertThat(count()).isZero();
    }

    // Helpers

    // The bank's layout, newest first, with Category and Other Description added. Four rows:
    // opening + 2,000 cash - 1,000 cheque + 1,500 card - 1,500 charges = opening + 1,000.
    private static String month(YearMonth month, String opening) {
        java.math.BigDecimal balance = new java.math.BigDecimal(opening);
        String[][] rows = {
                {"1", "CASH DEPOSIT", "Credit", "2000.00", "CASH_SALES_DEPOSIT", "", "000000000"},
                {"5", "CHQ PAID 605001", "Debit", "1000.00", "CHEQUE_PAYMENT", "", "000605001"},
                {"20", "MERCHANT SETTLEMENT", "Credit", "1500.00", "CARD_SALES_SETTLEMENT", "", "000000000"},
                {"28", "SERVICE CHARGE", "Debit", "1500.00", "OTHER", "Bank charges", "000000000"},
        };
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.time.format.DateTimeFormatter mdy = java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy");
        for (String[] row : rows) {
            java.math.BigDecimal amount = new java.math.BigDecimal(row[3]);
            boolean credit = row[2].equals("Credit");
            balance = credit ? balance.add(amount) : balance.subtract(amount);
            lines.add(String.join(",", month.atDay(Integer.parseInt(row[0])).format(mdy), row[1], row[2],
                    credit ? row[3] : "", credit ? "" : row[3], balance.toPlainString(), row[6], "631", row[4], row[5]));
        }
        java.util.Collections.reverse(lines);
        return HEADER + String.join("\n", lines) + "\n";
    }

    private ResultActions upload(YearMonth month, String content) throws Exception {
        var request = multipart(url()).file(file(content)).with(user());
        if (month != null) {
            request.param("month", month.toString());
        }
        return mockMvc.perform(request);
    }

    private String url() {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-accounts/" + accountId + "/monthly-imports";
    }

    private static MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "bank-transactions.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()));
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bank_transactions WHERE company_id = ?", Integer.class, companyId);
    }
}
