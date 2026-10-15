package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.statement.TransactionFingerprints;
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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Statement import, transactions and classification for a COMPANY_ADMIN of one company, using
 * the uploaded sample statement. Membership answers come from FakeIdentityServer over HTTP.
 * Needs the local bank-integration PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatementImportIntegrationTest {

    private static final String HEADER =
            "Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID\r\n";
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
        // Random IDs so the tests never collide with data in the local dev database.
        userId = UUID.randomUUID();
        companyId = UUID.randomUUID();
        accountId = insertAccount(companyId, "ACTIVE");
        identityService.reset();
        identityService.grant(companyId, "COMPANY_ADMIN");
    }

    @Test
    void shouldImportTheUploadedSampleStatement() throws Exception {
        byte[] sample = sample();

        String body = upload(accountId, file("Sample Bank Statement.csv", sample))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.filename").value("Sample Bank Statement.csv"))
                .andExpect(jsonPath("$.fileFormat").value("TRANSACTION_DETAILS_CSV"))
                .andExpect(jsonPath("$.fileSha256").value(TransactionFingerprints.sha256(sample)))
                .andExpect(jsonPath("$.periodStart").value("2026-09-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-09-30"))
                .andExpect(jsonPath("$.rowCount").value(59))
                .andExpect(jsonPath("$.importedRowCount").value(55))
                .andExpect(jsonPath("$.duplicateRowCount").value(0))
                .andExpect(jsonPath("$.skippedRowCount").value(4))
                .andExpect(jsonPath("$.importedBy").value(userId.toString()))
                .andExpect(jsonPath("$.importedAt", notNullValue()))
                .andExpect(jsonPath("$.validationErrors", hasSize(0)))
                .andExpect(jsonPath("$.earlierImportsOfSameFile", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND source_import_batch_id = '"
                + batchId + "'")).isEqualTo(55);
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND direction = 'CREDIT'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND direction = 'DEBIT'")).isEqualTo(43);
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND category = 'UNCLASSIFIED'"
                + " AND classification_method = 'UNKNOWN' AND classified_by IS NULL")).isEqualTo(55);
        // The three identical HUTCHISON TELECOM debits of 9/7 are all kept.
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ? AND transaction_date = '2026-09-07'"
                + " AND description = 'ECOMMERCE HUTCHISON TELECOM LA' AND amount = 100")).isEqualTo(3);

        Map<String, Object> newest = jdbcTemplate.queryForMap("""
                SELECT * FROM bank_transactions WHERE company_id = ? ORDER BY transaction_date DESC, entry_sequence DESC LIMIT 1
                """, companyId);
        assertThat(newest.get("description")).isEqualTo("INTEREST WITHHELD");
        assertThat(newest.get("direction")).isEqualTo("DEBIT");
        assertThat((BigDecimal) newest.get("amount")).isEqualByComparingTo("1.43");
        assertThat((BigDecimal) newest.get("running_balance")).isEqualByComparingTo("8373.55");
        assertThat(newest.get("cheque_number")).isNull();
        assertThat(newest.get("branch_code")).isEqualTo("631");

        Map<String, Object> snapshot = jdbcTemplate.queryForMap(
                "SELECT * FROM bank_balance_snapshots WHERE company_id = ?", companyId);
        assertThat((BigDecimal) snapshot.get("balance_amount")).isEqualByComparingTo("8373.55");
        assertThat(snapshot.get("source_type")).isEqualTo("STATEMENT_IMPORT");
        assertThat(snapshot.get("source_import_batch_id")).isEqualTo(batchId);
        assertThat(jdbcTemplate.queryForObject("SELECT balance_as_of FROM bank_balance_snapshots WHERE company_id = ?",
                OffsetDateTime.class, companyId).toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 9, 30, 23, 59, 59, 0, ZoneOffset.ofHoursMinutes(5, 30)).toInstant());

        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT action, actor_user_id, entity_id, after_data ->> 'importedRowCount' AS imported,
                       after_data ->> 'closingBalance' AS closing
                FROM bank_integration_audit_events WHERE company_id = ?
                """, companyId);
        assertThat(audit.get("action")).isEqualTo("BANK_STATEMENT_IMPORTED");
        assertThat(audit.get("actor_user_id")).isEqualTo(userId);
        assertThat(audit.get("entity_id")).isEqualTo(batchId);
        assertThat(audit.get("imported")).isEqualTo("55");
        assertThat(audit.get("closing")).isEqualTo("8373.55");

        assertThat(count("SELECT count(*) FROM outbox_events WHERE payload ->> 'companyId' = ?::text"
                + " AND event_type = 'BankTransactionImported'")).isEqualTo(55);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE payload ->> 'companyId' = ?::text"
                + " AND event_type = 'BankBalanceSnapshotRecorded'")).isEqualTo(1);
    }

    @Test
    void shouldNotDuplicateTransactionsWhenTheSameFileIsUploadedAgain() throws Exception {
        upload(accountId, file("statement.csv", sample())).andExpect(status().isCreated());

        upload(accountId, file("statement-again.csv", sample()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IMPORTED"))
                .andExpect(jsonPath("$.importedRowCount").value(0))
                .andExpect(jsonPath("$.duplicateRowCount").value(55))
                .andExpect(jsonPath("$.skippedRowCount").value(4));

        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ?")).isEqualTo(55);
        assertThat(count("SELECT count(*) FROM bank_balance_snapshots WHERE company_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isEqualTo(2);
    }

    @Test
    void shouldImportOnlyNewRowsFromAnOverlappingStatement() throws Exception {
        // An earlier statement holding the oldest 20 rows of the sample (9/1 to 9/9).
        List<String> lines = Arrays.asList(new String(sample(), StandardCharsets.US_ASCII).split("\r\n"));
        String older = lines.get(0) + "\r\n" + lines.get(1) + "\r\n"
                + String.join("\r\n", lines.subList(lines.size() - 20, lines.size())) + "\r\n";
        String body = upload(accountId, file("august-september.csv", older.getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int importedFirst = JsonPath.read(body, "$.importedRowCount");
        int skippedFirst = JsonPath.read(body, "$.skippedRowCount");
        assertThat(importedFirst + skippedFirst).isEqualTo(20);

        upload(accountId, file("september.csv", sample()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicateRowCount").value(importedFirst))
                .andExpect(jsonPath("$.importedRowCount").value(55 - importedFirst));

        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ?")).isEqualTo(55);
        assertThat(count("SELECT count(*) FROM bank_balance_snapshots WHERE company_id = ?")).isEqualTo(2);
    }

    @Test
    void shouldRejectInvalidStatementAndRecordFailedBatch() throws Exception {
        String csv = "TRANSACTION DETAILS,,,,,,,\r\n" + HEADER
                + "9/30/2026,ATM,Debit,,5,95.00,000000000,631\r\n"
                + "9/29/2026,DEPOSIT,Credit,,100,100.00,000000000,631\r\n";

        String body = upload(accountId, file("broken.csv", csv.getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Statement rejected"))
                .andExpect(jsonPath("$.errors[0].line").value(4))
                .andExpect(jsonPath("$.errors[0].field").value("Debit Amount"))
                .andReturn().getResponse().getContentAsString();
        String batchId = JsonPath.read(body, "$.batchId");

        mockMvc.perform(authenticated(get(importsUrl() + "/" + batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.importedRowCount").value(0))
                .andExpect(jsonPath("$.errorSummary").value("A credit transaction must not have a Debit Amount."))
                .andExpect(jsonPath("$.validationErrors[0].line").value(4));

        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM bank_balance_snapshots WHERE company_id = ?")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT action FROM bank_integration_audit_events WHERE company_id = ?",
                String.class, companyId)).isEqualTo("BANK_STATEMENT_REJECTED");
    }

    @Test
    void shouldRejectUnsupportedUploadsBeforeParsing() throws Exception {
        upload(accountId, file("statement.txt", sample())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only CSV statement files (.csv) are supported."));
        upload(accountId, new MockMultipartFile("file", "statement.csv", "image/png", sample()))
                .andExpect(status().isBadRequest());
        upload(accountId, file("statement.csv", new byte[0])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose a statement file to upload."));
        upload(accountId, file("statement.csv", new byte[2 * 1024 * 1024 + 1])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The statement file must be at most 2 MB."));
        mockMvc.perform(authenticated(multipart(accountImportsUrl(accountId))))
                .andExpect(status().isBadRequest());

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isZero();
    }

    @Test
    void shouldStoreOnlyTheFileNameWithoutClientPath() throws Exception {
        upload(accountId, file("C:\\Users\\someone\\Downloads\\stmt<sept>.csv", sample()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("stmt_sept_.csv"));
    }

    @Test
    void shouldOnlyImportIntoTheCompanysActiveCurrentAccount() throws Exception {
        UUID inactive = insertAccount(companyId, "INACTIVE");
        UUID otherCompanysAccount = insertAccount(UUID.randomUUID(), "ACTIVE");

        upload(inactive, file("statement.csv", sample())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Statements can only be imported into an active current account."));
        upload(otherCompanysAccount, file("statement.csv", sample())).andExpect(status().isNotFound());
        upload(UUID.randomUUID(), file("statement.csv", sample())).andExpect(status().isNotFound());

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isZero();
    }

    @Test
    void shouldListTransactionsByActualTransactionDateNewestFirst() throws Exception {
        upload(accountId, file("statement.csv", sample())).andExpect(status().isCreated());

        mockMvc.perform(authenticated(get(transactionsUrl() + "?from=2026-09-29&to=2026-09-30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].description").value(contains(
                        "INTEREST WITHHELD", "INTEREST PAY SYS-GEN", "Exchange", "ECOMMERCE COURSERA*725587305")));
        mockMvc.perform(authenticated(get(transactionsUrl() + "?category=UNCLASSIFIED&bankAccountId=" + accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(55)))
                .andExpect(jsonPath("$[*].companyId").value(everyItem(is(companyId.toString()))));
        mockMvc.perform(authenticated(get(transactionsUrl() + "?from=2026-09-30&to=2026-09-01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnTransactionDetailsWithinTheCompanyOnly() throws Exception {
        upload(accountId, file("statement.csv", sample())).andExpect(status().isCreated());
        UUID transactionId = transactionId("INTEREST PAY SYS-GEN");

        mockMvc.perform(authenticated(get(transactionsUrl() + "/" + transactionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionDate").value("2026-09-30"))
                .andExpect(jsonPath("$.direction").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(14.32))
                .andExpect(jsonPath("$.runningBalance").value(8374.98))
                .andExpect(jsonPath("$.category").value("UNCLASSIFIED"));
        mockMvc.perform(authenticated(get(transactionsUrl() + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldClassifyTransactionWithAuditHistory() throws Exception {
        upload(accountId, file("statement.csv", sample())).andExpect(status().isCreated());
        UUID credit = transactionId("Hirefly colombo tiffany");

        classify(credit, "CASH_SALES_DEPOSIT", "Shop takings deposited by the cashier")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("CASH_SALES_DEPOSIT"))
                .andExpect(jsonPath("$.classificationMethod").value("MANUAL"))
                .andExpect(jsonPath("$.classifiedBy").value(userId.toString()))
                .andExpect(jsonPath("$.classifiedAt", notNullValue()));

        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT action, actor_user_id, actor_type, reason, before_data ->> 'category' AS before_category,
                       after_data ->> 'category' AS after_category
                FROM bank_integration_audit_events WHERE company_id = ? AND entity_id = ?
                """, companyId, credit);
        assertThat(audit.get("action")).isEqualTo("TRANSACTION_CLASSIFIED");
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat(audit.get("actor_user_id")).isEqualTo(userId);
        assertThat(audit.get("reason")).isEqualTo("Shop takings deposited by the cashier");
        assertThat(audit.get("before_category")).isEqualTo("UNCLASSIFIED");
        assertThat(audit.get("after_category")).isEqualTo("CASH_SALES_DEPOSIT");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE payload ->> 'companyId' = ?::text"
                + " AND event_type = 'BankTransactionCategoryChanged'")).isEqualTo(1);

        // Classifying again with the same category changes nothing and records nothing.
        classify(credit, "CASH_SALES_DEPOSIT", null).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM bank_integration_audit_events WHERE company_id = ?"
                + " AND action = 'TRANSACTION_CLASSIFIED'")).isEqualTo(1);
    }

    @Test
    void shouldOnlyAllowCategoriesThatMatchTheDirection() throws Exception {
        upload(accountId, file("statement.csv", sample())).andExpect(status().isCreated());
        UUID credit = transactionId("Hirefly colombo tiffany");
        UUID debit = transactionId("ECOMMERCE COURSERA*725587305");

        classify(debit, "CASH_SALES_DEPOSIT", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("CASH_SALES_DEPOSIT cannot be used for a DEBIT transaction."));
        classify(debit, "CARD_SALES_SETTLEMENT", null).andExpect(status().isBadRequest());
        classify(credit, "CHEQUE_PAYMENT", null).andExpect(status().isBadRequest());
        classify(debit, "CHEQUE_PAYMENT", null).andExpect(status().isOk());
        classify(credit, "OTHER", null).andExpect(status().isOk());
        classify(credit, "UNCLASSIFIED", null).andExpect(status().isOk());
        classify(credit, "SALES", null).andExpect(status().isBadRequest());
        mockMvc.perform(authenticated(put(transactionsUrl() + "/" + credit + "/classification"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.category").value("category is required"));
    }

    private ResultActions upload(UUID bankAccountId, MockMultipartFile file) throws Exception {
        return mockMvc.perform(authenticated(multipart(accountImportsUrl(bankAccountId)).file(file)));
    }

    private ResultActions classify(UUID transactionId, String category, String reason) throws Exception {
        String body = reason == null
                ? "{\"category\": \"" + category + "\"}"
                : "{\"category\": \"" + category + "\", \"reason\": \"" + reason + "\"}";
        return mockMvc.perform(authenticated(put(transactionsUrl() + "/" + transactionId + "/classification"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B authenticated(B request) {
        return request.with(jwt().jwt(jwt -> jwt.subject(userId.toString())));
    }

    private String accountImportsUrl(UUID bankAccountId) {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-accounts/" + bankAccountId + "/statement-imports";
    }

    private String importsUrl() {
        return "/api/v1/bank-integration/companies/" + companyId + "/statement-imports";
    }

    private String transactionsUrl() {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-transactions";
    }

    private UUID transactionId(String description) {
        return jdbcTemplate.queryForObject("SELECT id FROM bank_transactions WHERE company_id = ? AND description = ?"
                + " ORDER BY entry_sequence DESC LIMIT 1", UUID.class, companyId, description);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, companyId);
    }

    private UUID insertAccount(UUID companyId, String status) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Commercial Bank of Ceylon', 'Current', 'CURRENT', 'STATEMENT_IMPORT', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, status);
    }

    private static MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "text/csv", content);
    }

    static byte[] sample() throws IOException {
        try (InputStream in = StatementImportIntegrationTest.class.getResourceAsStream("/statements/sample-bank-statement.csv")) {
            return in.readAllBytes();
        }
    }
}
