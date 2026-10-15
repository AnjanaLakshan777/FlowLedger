package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.repository.AuditEventStore;
import com.flowledger.cashmanagement.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrent uploads and decisions, and the all-or-nothing approval transaction. Deliberately not
 * {@code @Transactional}: each request commits on its own, as in production. Test data is deleted
 * afterwards. Needs the local cash-management PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesImportConcurrencyIntegrationTest {

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
    private AuditEventStore audit;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        identityService.reset();
        identityService.grant(companyId, "FINANCE_CONTROLLER");
    }

    // The audit table is append-only, so the committed test data can only be removed with the
    // database's trigger bypass, available to the local superuser. Test cleanup only.
    @AfterEach
    void deleteTestData() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            jdbcTemplate.update("DELETE FROM cash_management_audit_events WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM daily_sales WHERE company_id = ?", companyId);
            jdbcTemplate.update("""
                    DELETE FROM sales_import_rows
                    WHERE batch_id IN (SELECT id FROM sales_import_batches WHERE company_id = ?)
                    """, companyId);
            jdbcTemplate.update("DELETE FROM sales_import_batches WHERE company_id = ?", companyId);
        });
    }

    @Test
    void shouldApproveAnImportOnceWhenTwoControllersApproveAtOnce() throws Exception {
        String id = upload("a.csv", "2025-01-01,1,1", "2025-01-02,2,2");

        List<Integer> statuses = concurrently(() -> approve(id).getStatus(), () -> approve(id).getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM cash_management_audit_events
                WHERE company_id = ? AND action = 'SALES_IMPORT_APPROVED'
                """)).isEqualTo(1);
    }

    @Test
    void shouldApproveOnlyOneOfTwoImportsWithTheSameDate() throws Exception {
        String first = upload("a.csv", "2025-01-01,1,1", "2025-01-02,2,2");
        String second = upload("b.csv", "2025-01-02,9,9", "2025-01-03,3,3");

        List<Integer> statuses = concurrently(() -> approve(first).getStatus(), () -> approve(second).getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        // The winner's rows only; the other import recorded nothing and still waits.
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM sales_import_batches WHERE company_id = ? ORDER BY status
                """, String.class, companyId)).containsExactly("APPROVED", "PENDING_APPROVAL");
        assertThat(count("""
                SELECT count(*) FROM daily_sales d JOIN sales_import_batches b ON b.id = d.import_batch_id
                WHERE d.company_id = ? AND b.status <> 'APPROVED'
                """)).isZero();
    }

    @Test
    void shouldKeepOneOfTwoIdenticalUploadsMadeAtOnce() throws Exception {
        String content = csv("2025-01-01,1,1");

        List<Integer> statuses = concurrently(() -> uploadResponse("a.csv", content).getStatus(),
                () -> uploadResponse("a.csv", content).getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(count("SELECT count(*) FROM sales_import_batches WHERE company_id = ?")).isEqualTo(1);
    }

    @Test
    void shouldLeaveNothingWhenTheApprovalCannotBeCompleted() throws Exception {
        String id = upload("a.csv", "2025-01-01,1,1", "2025-01-02,2,2");
        doThrow(new TransientDataAccessResourceException("connection lost"))
                .when(audit).record(eq(companyId), any(), eq("SALES_IMPORT_APPROVED"), any(), any(), any(), any(), any());

        assertThat(approve(id).getStatus()).isEqualTo(500);

        // The daily sales written before the failure were rolled back with the status change.
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sales_import_batches WHERE id = ?::uuid",
                String.class, id)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM sales_import_batches WHERE id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    // Helpers

    private String upload(String filename, String... rows) throws Exception {
        MockHttpServletResponse response = uploadResponse(filename, csv(rows));
        assertThat(response.getStatus()).isEqualTo(201);
        return JsonPath.read(response.getContentAsString(), "$.batch.id");
    }

    private MockHttpServletResponse uploadResponse(String filename, String content) throws Exception {
        return mockMvc.perform(multipart("/api/v1/cash-management/companies/" + companyId + "/sales-imports")
                        .file(new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                        .with(user()))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse approve(String id) throws Exception {
        return mockMvc.perform(post("/api/v1/cash-management/companies/" + companyId + "/sales-imports/" + id + "/approve")
                        .with(user()).contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1}"))
                .andReturn().getResponse();
    }

    @SafeVarargs
    private List<Integer> concurrently(Callable<Integer>... tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.length);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String csv(String... rows) {
        return "date,cash_sales,card_payments\n" + String.join("\n", rows) + "\n";
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, companyId);
    }

}
