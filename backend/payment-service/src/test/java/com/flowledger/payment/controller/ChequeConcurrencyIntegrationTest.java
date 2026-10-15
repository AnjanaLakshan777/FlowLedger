package com.flowledger.payment.controller;

import com.flowledger.payment.support.FakeBankIntegrationServer;
import com.flowledger.payment.support.FakeIdentityServer;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrency and transaction boundaries. Deliberately not @Transactional: each request commits
 * in its own transaction, as in production. Needs the local payment PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChequeConcurrencyIntegrationTest {

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

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID companyId;
    private UUID bankAccountId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        bankAccountId = UUID.randomUUID();
        today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        identityService.reset();
        bankIntegration.reset();
    }

    // Audit history is append-only, so the committed test data can only be removed with the
    // database's trigger bypass, available to the local superuser. Test cleanup only.
    @AfterEach
    void deleteTestData() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            jdbcTemplate.update("DELETE FROM outbox_events WHERE payload ->> 'companyId' = ?", companyId.toString());
            jdbcTemplate.update("DELETE FROM payment_audit_events WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM cheques WHERE company_id = ?", companyId);
        });
    }

    @Test
    void shouldAcceptOnlyOneOfTwoConcurrentEditsOfTheSameVersion() throws Exception {
        UUID chequeId = insertCheque();
        CyclicBarrier bothAuthorized = new CyclicBarrier(2);
        // Hold both requests at the membership check, then release them together.
        identityService.grantAfter(companyId, "FINANCE_CONTROLLER", () -> await(bothAuthorized));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> edits = List.of(edit(chequeId, "11000.00"), edit(chequeId, "22000.00"));
            for (Future<Integer> result : executor.invokeAll(edits, 30, TimeUnit.SECONDS)) {
                statuses.add(result.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM cheques WHERE id = ?", Integer.class, chequeId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_audit_events WHERE cheque_id = ? AND action = 'CHEQUE_UPDATED'",
                Integer.class, chequeId)).isEqualTo(1);
    }

    // Slow identity or bank services must not tie up database connections: both calls finish
    // before any transaction takes a connection from the pool.
    @Test
    void shouldNotHoldDatabaseConnectionWhileOtherServicesAnswer() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        List<Integer> activeConnections = new CopyOnWriteArrayList<>();
        identityService.grantAfter(companyId, "FINANCE_CONTROLLER", () -> activeConnections.add(pool.getActiveConnections()));
        bankIntegration.currentAccountWhile(companyId, bankAccountId, () -> activeConnections.add(pool.getActiveConnections()));

        mockMvc.perform(post(chequesUrl()).with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chequeNumber": "000555", "payeeName": "Lanka Tiles", "category": "SUPPLIER_PAYMENT",
                                 "amount": 45000.00, "issueDate": "%s", "expectedClearingDate": "%s"}
                                """.formatted(today, today.plusDays(10))))
                .andExpect(status().isCreated());
        mockMvc.perform(get(chequesUrl()).with(user())).andExpect(status().isOk());

        // Identity check and bank lookup for the add, then the identity check for the list.
        assertThat(activeConnections).containsExactly(0, 0, 0);
    }

    private Callable<Integer> edit(UUID chequeId, String amount) {
        return () -> mockMvc.perform(put(chequesUrl() + "/" + chequeId).with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chequeNumber": "000100", "payeeName": "Lanka Tiles", "category": "SUPPLIER_PAYMENT",
                                 "amount": %s, "issueDate": "%s", "expectedClearingDate": "%s", "version": 1}
                                """.formatted(amount, today, today.plusDays(10))))
                .andReturn().getResponse().getStatus();
    }

    private UUID insertCheque() {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO cheques (company_id, bank_account_id, cheque_number, payee_name, category, amount,
                                             issue_date, expected_clearing_date, status, created_by)
                        VALUES (?, ?, '000100', 'Lanka Tiles', 'SUPPLIER_PAYMENT', 45000.00, ?, ?, 'ISSUED', ?)
                        RETURNING id
                        """,
                UUID.class, companyId, bankAccountId, today, today.plusDays(10), UUID.randomUUID());
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    private String chequesUrl() {
        return "/api/v1/payment/companies/" + companyId + "/cheques";
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent requests did not all reach the membership check", e);
        }
    }
}
