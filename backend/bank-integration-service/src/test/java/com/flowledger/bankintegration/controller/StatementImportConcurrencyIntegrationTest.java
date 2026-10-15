package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.repository.StatementImportStore;
import com.flowledger.bankintegration.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Concurrent and repeated imports, and that a failed import leaves nothing behind. Deliberately
 * not @Transactional: each request commits in its own transaction, as in production. Needs the
 * local bank-integration PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementImportConcurrencyIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();

    @DynamicPropertySource
    static void identityServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private StatementImportStore statementImportStore;

    private UUID companyId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountId = jdbcTemplate.queryForObject("""
                        INSERT INTO bank_accounts (company_id, bank_name, account_name, account_type, integration_mode, status)
                        VALUES (?, 'Commercial Bank of Ceylon', 'Current', 'CURRENT', 'STATEMENT_IMPORT', 'ACTIVE')
                        RETURNING id
                        """,
                UUID.class, companyId);
        identityService.reset();
    }

    // Audit history is append-only, so the committed test data can only be removed with the
    // database's trigger bypass, available to the local superuser. Test cleanup only.
    @AfterEach
    void deleteTestData() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            jdbcTemplate.update("DELETE FROM outbox_events WHERE payload ->> 'companyId' = ?", companyId.toString());
            jdbcTemplate.update("DELETE FROM bank_integration_audit_events WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM bank_balance_snapshots WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM bank_transactions WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM bank_statement_import_batches WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM bank_accounts WHERE company_id = ?", companyId);
        });
    }

    @Test
    void shouldStoreEachTransactionOnceWhenTheSameFileIsImportedConcurrently() throws Exception {
        CyclicBarrier bothAuthorized = new CyclicBarrier(2);
        // Hold both uploads at the membership check, then release them together.
        identityService.grantAfter(companyId, "FINANCE_CONTROLLER", () -> await(bothAuthorized));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try {
            List<Callable<MockHttpServletResponse>> uploads = List.of(this::upload, this::upload);
            for (Future<MockHttpServletResponse> result : executor.invokeAll(uploads, 60, TimeUnit.SECONDS)) {
                responses.add(result.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactly(201, 201);
        List<Integer> imported = new ArrayList<>();
        List<Integer> duplicates = new ArrayList<>();
        for (MockHttpServletResponse response : responses) {
            imported.add(JsonPath.read(response.getContentAsString(), "$.importedRowCount"));
            duplicates.add(JsonPath.read(response.getContentAsString(), "$.duplicateRowCount"));
        }
        // Rows are inserted in the same order by both, so one import takes them all.
        assertThat(imported).containsExactlyInAnyOrder(55, 0);
        assertThat(duplicates).containsExactlyInAnyOrder(0, 55);

        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ?")).isEqualTo(55);
        assertThat(count("SELECT count(DISTINCT deduplication_fingerprint) FROM bank_transactions WHERE company_id = ?"))
                .isEqualTo(55);
        assertThat(count("SELECT count(*) FROM bank_balance_snapshots WHERE company_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE payload ->> 'companyId' = ?::text"
                + " AND event_type = 'BankTransactionImported'")).isEqualTo(55);
    }

    @Test
    void shouldReportEarlierImportsOfTheSameFile() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");

        String first = upload().getContentAsString();
        String second = upload().getContentAsString();

        assertThat(JsonPath.<List<String>>read(first, "$.earlierImportsOfSameFile")).isEmpty();
        assertThat(JsonPath.<List<String>>read(second, "$.earlierImportsOfSameFile"))
                .containsExactly(JsonPath.<String>read(first, "$.id"));
        assertThat(JsonPath.<Integer>read(second, "$.duplicateRowCount")).isEqualTo(55);
    }

    @Test
    void shouldLeaveNothingBehindWhenTheImportFailsPartWay() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        // Every transaction row is already inserted when the snapshot fails.
        doThrow(new IllegalStateException("simulated database failure"))
                .when(statementImportStore).insertBalanceSnapshot(eq(companyId), any(), any(), any(), any(), any());

        assertThat(upload().getStatus()).isEqualTo(500);

        assertThat(count("SELECT count(*) FROM bank_statement_import_batches WHERE company_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM bank_transactions WHERE company_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM bank_balance_snapshots WHERE company_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM bank_integration_audit_events WHERE company_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE payload ->> 'companyId' = ?::text")).isZero();
    }

    private MockHttpServletResponse upload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", "text/csv", StatementImportIntegrationTest.sample());
        return mockMvc.perform(multipart("/api/v1/bank-integration/companies/" + companyId + "/bank-accounts/"
                                + accountId + "/statement-imports")
                        .file(file)
                        .with(jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()))))
                .andReturn().getResponse();
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, companyId);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent uploads did not both reach the membership check", e);
        }
    }
}
