package com.flowledger.identity.controller;

import com.flowledger.identity.repository.UserRepository;
import com.flowledger.identity.service.AccessTokenService;
import com.flowledger.identity.support.TestIdentities;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The signed-in user's own profile and usable company memberships. Needs the local PostgreSQL
 * database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CurrentUserIntegrationTest {

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

    private TestIdentities identities;

    @BeforeEach
    void setUp() {
        identities = new TestIdentities(jdbcTemplate, userRepository, accessTokenService, entityManager);
    }

    @Test
    void shouldReturnOnlyMembershipsTheUserCanActThrough() throws Exception {
        UUID active = identities.company("ACTIVE");
        UUID suspendedCompany = identities.company("SUSPENDED");
        UUID invitedCompany = identities.company("ACTIVE");
        UUID userId = identities.user("ACTIVE", "USER");
        UUID membershipId = identities.membership(active, userId, "FINANCE_CONTROLLER", "ACTIVE");
        identities.membership(suspendedCompany, userId, "ACCOUNTANT", "ACTIVE");
        identities.membership(invitedCompany, userId, "CASHIER", "INVITED");

        me(userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.fullName").value("Test User"))
                .andExpect(jsonPath("$.platformRole").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.memberships", hasSize(1)))
                .andExpect(jsonPath("$.memberships[0].membershipId").value(membershipId.toString()))
                .andExpect(jsonPath("$.memberships[0].companyId").value(active.toString()))
                .andExpect(jsonPath("$.memberships[0].companyName").isString())
                .andExpect(jsonPath("$.memberships[0].companyRole").value("FINANCE_CONTROLLER"));
    }

    // A system admin without memberships sees the platform role and no companies.
    @Test
    void shouldShowPlatformRoleWithoutGrantingCompanies() throws Exception {
        UUID admin = identities.systemAdmin();

        me(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformRole").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.memberships", hasSize(0)));
    }

    @Test
    void shouldRefuseAnAccountThatIsNoLongerActive() throws Exception {
        UUID userId = identities.member(identities.company("ACTIVE"), "ACCOUNTANT");
        String token = identities.bearerToken(userId);
        jdbcTemplate.update("UPDATE users SET account_status = 'LOCKED' WHERE id = ?", userId);

        mockMvc.perform(get("/api/v1/identity/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Your account is not active."));
    }

    @Test
    void shouldRequireAToken() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me")).andExpect(status().isUnauthorized());
    }

    private ResultActions me(UUID userId) throws Exception {
        return mockMvc.perform(get("/api/v1/identity/me").header(HttpHeaders.AUTHORIZATION, identities.bearerToken(userId)));
    }
}
