package com.flowledger.forecast.controller;

import com.flowledger.forecast.support.FakeIdentityServer;
import com.flowledger.forecast.support.FakeSourceServices;
import com.flowledger.forecast.support.FixedClockConfig;
import com.flowledger.forecast.support.ProjectedCheques;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
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

import static com.flowledger.forecast.support.ProjectedCheques.cheque;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrent forecast requests and transaction boundaries. Deliberately not @Transactional: each
 * request commits in its own transaction, as in production. Needs the local forecast PostgreSQL
 * database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
class ForecastConcurrencyIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeSourceServices sources = FakeSourceServices.instance();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
        registry.add("flowledger.bank-integration.base-url", sources::baseUrl);
        registry.add("flowledger.payment.base-url", sources::baseUrl);
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
    private ProjectedCheques projected;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        projected = new ProjectedCheques(jdbcTemplate);
        UUID accountId = UUID.randomUUID();
        identityService.reset();
        sources.reset();
        sources.currentAccount(companyId, accountId);
        sources.balance(companyId, accountId, "250000.00", "2026-10-14T18:29:59Z");
        sources.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14));
        sources.transactions(companyId, List.of());
        projected.cheques(companyId, List.of(
                cheque(UUID.randomUUID(), accountId, "000123", "1000.00", LocalDate.of(2026, 10, 20), "ISSUED")));
    }

    // Saved runs are immutable, so the committed test data can only be removed with the
    // database's trigger bypass, available to the local superuser. Test cleanup only.
    @AfterEach
    void deleteTestData() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
            jdbcTemplate.update("""
                    DELETE FROM forecast_run_cheques
                    WHERE forecast_run_id IN (SELECT id FROM forecast_runs WHERE company_id = ?)
                    """, companyId);
            jdbcTemplate.update("""
                    DELETE FROM forecast_daily_results
                    WHERE forecast_run_id IN (SELECT id FROM forecast_runs WHERE company_id = ?)
                    """, companyId);
            jdbcTemplate.update("DELETE FROM forecast_runs WHERE company_id = ?", companyId);
            jdbcTemplate.update("DELETE FROM forecast_model_configs WHERE company_id = ?", companyId);
        });
        projected.deleteAll();
    }

    @Test
    void shouldSaveOneRunWhenTheSameForecastIsRequestedTwiceAtOnce() throws Exception {
        CyclicBarrier bothAuthorized = new CyclicBarrier(2);
        // Hold both requests at the membership check, then release them together.
        identityService.grantAfter(companyId, "FINANCE_CONTROLLER", () -> await(bothAuthorized));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<MockHttpServletResponse> responses = new ArrayList<>();
        try {
            List<Callable<MockHttpServletResponse>> requests = List.of(this::create, this::create);
            for (Future<MockHttpServletResponse> result : executor.invokeAll(requests, 60, TimeUnit.SECONDS)) {
                responses.add(result.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 200);
        assertThat(JsonPath.<String>read(responses.get(0).getContentAsString(), "$.run.id"))
                .isEqualTo(JsonPath.read(responses.get(1).getContentAsString(), "$.run.id"));
        assertThat(count("SELECT count(*) FROM forecast_runs WHERE company_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM forecast_model_configs WHERE company_id = ?")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM forecast_daily_results
                WHERE forecast_run_id IN (SELECT id FROM forecast_runs WHERE company_id = ?)
                """)).isEqualTo(84);
    }

    // Slow source services must not tie up database connections: every call to them finishes
    // before the run's transaction takes a connection from the pool.
    @Test
    void shouldNotHoldADatabaseConnectionWhileOtherServicesAnswer() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        List<Integer> activeConnections = new CopyOnWriteArrayList<>();
        identityService.grantAfter(companyId, "FINANCE_CONTROLLER", () -> activeConnections.add(pool.getActiveConnections()));
        sources.onRequest(() -> activeConnections.add(pool.getActiveConnections()));

        assertThat(create().getStatus()).isEqualTo(201);

        // Identity check, then account, balance, statements and transactions; cheques come from the
        // projection, not from another service.
        assertThat(activeConnections).containsExactly(0, 0, 0, 0, 0);
    }

    private MockHttpServletResponse create() throws Exception {
        return mockMvc.perform(post("/api/v1/forecast/companies/" + companyId + "/forecast-runs")
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
            throw new IllegalStateException("Concurrent requests did not both reach the membership check", e);
        }
    }
}
