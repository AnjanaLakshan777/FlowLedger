package com.flowledger.identity.controller;

import com.flowledger.identity.repository.UserRepository;
import com.flowledger.identity.service.AccessTokenService;
import com.flowledger.identity.support.TestIdentities;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * The last active company admin must survive concurrent demotions. Deliberately not
 * @Transactional, so each request commits in its own transaction as in production; the test
 * data is removed afterwards. Needs the local PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MembershipConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private EntityManager entityManager;

    private UUID companyId;

    @AfterEach
    void deleteTestData() {
        List<UUID> users = jdbcTemplate.queryForList(
                "SELECT user_id FROM company_memberships WHERE company_id = ?", UUID.class, companyId);
        jdbcTemplate.update("DELETE FROM company_memberships WHERE company_id = ?", companyId);
        users.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
        jdbcTemplate.update("DELETE FROM companies WHERE id = ?", companyId);
    }

    @RepeatedTest(3)
    void shouldKeepOneActiveCompanyAdminWhenTwoAdminsDemoteEachOther() throws Exception {
        TestIdentities identities = new TestIdentities(jdbcTemplate, userRepository, accessTokenService, entityManager);
        companyId = identities.company("ACTIVE");
        UUID firstAdmin = identities.member(companyId, "COMPANY_ADMIN");
        UUID secondAdmin = identities.member(companyId, "COMPANY_ADMIN");
        String firstToken = identities.bearerToken(firstAdmin);
        String secondToken = identities.bearerToken(secondAdmin);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(demote(start, firstToken, membershipOf(secondAdmin)));
            Future<Integer> second = executor.submit(demote(start, secondToken, membershipOf(firstAdmin)));
            start.countDown();

            List<Integer> statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        Integer activeAdmins = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM company_memberships
                WHERE company_id = ? AND company_role = 'COMPANY_ADMIN' AND status = 'ACTIVE'
                """, Integer.class, companyId);
        assertThat(activeAdmins).isEqualTo(1);
    }

    private Callable<Integer> demote(CountDownLatch start, String bearerToken, UUID membershipId) {
        return () -> {
            start.await();
            return mockMvc.perform(patch("/api/v1/identity/companies/" + companyId + "/memberships/" + membershipId)
                            .header(HttpHeaders.AUTHORIZATION, bearerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"companyRole\": \"ACCOUNTANT\"}"))
                    .andReturn().getResponse().getStatus();
        };
    }

    private UUID membershipOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM company_memberships WHERE company_id = ? AND user_id = ?", UUID.class, companyId, userId);
    }
}
