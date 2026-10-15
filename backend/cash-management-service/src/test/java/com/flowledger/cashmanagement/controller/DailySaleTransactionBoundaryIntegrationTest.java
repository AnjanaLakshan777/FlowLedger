package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
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

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transaction boundaries of the daily sales API. Deliberately not @Transactional: each request
 * must run in its own real transaction, as in production, so rows are committed and removed
 * again after each test. Needs the local cash-management PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DailySaleTransactionBoundaryIntegrationTest {

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
    private DataSource dataSource;

    private UUID companyId;
    private LocalDate yesterday;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        yesterday = LocalDate.now(ZoneId.of("Asia/Colombo")).minusDays(1);
        identityService.reset();
    }

    @AfterEach
    void deleteTestData() {
        jdbcTemplate.update("DELETE FROM daily_sales WHERE company_id = ?", companyId);
    }

    // A slow identity-service must not tie up database connections: the membership check has to
    // finish before any transaction takes a connection from the pool.
    @Test
    void shouldNotHoldDatabaseConnectionWhileIdentityServiceIsChecking() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        List<Integer> activeConnectionsDuringCheck = new CopyOnWriteArrayList<>();
        identityService.grantAfter(companyId, "ACCOUNTANT",
                () -> activeConnectionsDuringCheck.add(pool.getActiveConnections()));

        String body = mockMvc.perform(createRequest())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String saleId = JsonPath.read(body, "$.id");
        mockMvc.perform(get(salesUrl() + "/" + saleId).with(user()))
                .andExpect(status().isOk());
        mockMvc.perform(get(salesUrl()).with(user()))
                .andExpect(status().isOk());

        assertThat(activeConnectionsDuringCheck).containsExactly(0, 0, 0);
    }

    @Test
    void shouldAcceptOnlyOneOfConcurrentSubmissionsForTheSameDate() throws Exception {
        int submissions = 4;
        CyclicBarrier everyoneAuthorized = new CyclicBarrier(submissions);
        // Hold each request at the membership check until all have arrived, then release them
        // together so they race to record the same company and sale date.
        identityService.grantAfter(companyId, "CASHIER", () -> await(everyoneAuthorized));

        ExecutorService executor = Executors.newFixedThreadPool(submissions);
        List<Integer> statuses;
        try {
            List<Callable<Integer>> requests = Collections.nCopies(submissions,
                    () -> mockMvc.perform(createRequest()).andReturn().getResponse().getStatus());
            statuses = new ArrayList<>();
            for (Future<Integer> result : executor.invokeAll(requests, 30, TimeUnit.SECONDS)) {
                statuses.add(result.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_sales WHERE company_id = ?", Integer.class, companyId)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest() {
        return post(salesUrl())
                .with(user())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"saleDate": "%s", "cashSalesAmount": 50000.00, "cardSalesAmount": 0}
                        """.formatted(yesterday));
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }

    private String salesUrl() {
        return "/api/v1/cash-management/companies/" + companyId + "/sales";
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Concurrent requests did not all reach the membership check", e);
        }
    }
}
