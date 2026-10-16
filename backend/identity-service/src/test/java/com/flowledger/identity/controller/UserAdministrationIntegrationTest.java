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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Users & Settings: adding users to a company, company details and the access-change history.
 * Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAdministrationIntegrationTest {

    private static final String PASSWORD = "Temporary-pass-1";

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
    private UUID companyId;
    private UUID companyAdmin;
    private UUID financeController;

    @BeforeEach
    void setUp() {
        identities = new TestIdentities(jdbcTemplate, userRepository, accessTokenService, entityManager);
        companyId = identities.company("ACTIVE");
        companyAdmin = identities.member(companyId, "COMPANY_ADMIN");
        financeController = identities.member(companyId, "FINANCE_CONTROLLER");
    }

    // Adding users

    @Test
    void shouldCreateAnActiveAccountWithTheTemporaryPassword() throws Exception {
        String email = newEmail();
        create(companyAdmin, body(email.toUpperCase(), "  New Person ", "FINANCE_CONTROLLER", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountCreated").value(true))
                .andExpect(jsonPath("$.membership.email").value(email))
                .andExpect(jsonPath("$.membership.fullName").value("New Person"))
                .andExpect(jsonPath("$.membership.companyRole").value("FINANCE_CONTROLLER"))
                .andExpect(jsonPath("$.membership.status").value("ACTIVE"))
                .andExpect(jsonPath("$.membership.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.membership.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.membership.temporaryPassword").doesNotExist());

        UUID userId = userIdOf(email);
        assertThat(jdbcTemplate.queryForObject("SELECT platform_role FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("USER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invited_by FROM company_memberships WHERE company_id = ? AND user_id = ?",
                UUID.class, companyId, userId)).isEqualTo(companyAdmin);
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId))
                .doesNotContain(PASSWORD);

        // The new user can sign in with the temporary password straight away.
        mockMvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void shouldAddAnExistingAccountWithoutChangingItsNameOrPassword() throws Exception {
        UUID existing = identities.user("ACTIVE", "USER");
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, existing);

        create(companyAdmin, body(email, "Another Name", "CASHIER", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountCreated").value(false))
                .andExpect(jsonPath("$.membership.userId").value(existing.toString()))
                .andExpect(jsonPath("$.membership.fullName").value("Test User"))
                .andExpect(jsonPath("$.membership.companyRole").value("CASHIER"));

        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, existing))
                .isEqualTo("{bcrypt}not-used-in-these-tests");
    }

    @Test
    void shouldRequireATemporaryPasswordOnlyForANewAccount() throws Exception {
        create(companyAdmin, body(newEmail(), "New Person", "CASHIER", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.temporaryPassword").isNotEmpty());
        create(companyAdmin, body(newEmail(), "New Person", "CASHIER", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.temporaryPassword").isNotEmpty());

        UUID existing = identities.user("ACTIVE", "USER");
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, existing);
        create(companyAdmin, body(email, "Test User", "CASHIER", null)).andExpect(status().isCreated());
    }

    @Test
    void shouldRefuseAUserWhoIsAlreadyAMember() throws Exception {
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, financeController);
        create(companyAdmin, body(email, "Test User", "CASHIER", null)).andExpect(status().isConflict());
    }

    @Test
    void shouldLetOnlyCompanyAdministrationAddUsers() throws Exception {
        create(financeController, body(newEmail(), "New Person", "CASHIER", PASSWORD)).andExpect(status().isForbidden());

        UUID otherCompany = identities.company("ACTIVE");
        UUID otherAdmin = identities.member(otherCompany, "COMPANY_ADMIN");
        create(otherAdmin, body(newEmail(), "New Person", "CASHIER", PASSWORD)).andExpect(status().isForbidden());

        create(identities.systemAdmin(), body(newEmail(), "New Person", "COMPANY_ADMIN", PASSWORD))
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE company_id = ?", Integer.class, companyId)).isEqualTo(3);
    }

    // Company details

    @Test
    void shouldShowCompanyDetailsToItsMembersOnly() throws Exception {
        UUID cashier = identities.member(companyId, "CASHIER");
        getCompany(cashier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(companyId.toString()))
                .andExpect(jsonPath("$.defaultCurrency").value("LKR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Colombo"));

        UUID outsider = identities.member(identities.company("ACTIVE"), "COMPANY_ADMIN");
        getCompany(outsider).andExpect(status().isForbidden());
        getCompany(identities.systemAdmin()).andExpect(status().isOk());
    }

    @Test
    void shouldLetCompanyAdminsEditTheNameAndRegistrationNumber() throws Exception {
        updateCompany(companyAdmin, "{\"name\": \"  Renamed Ltd \", \"registrationNumber\": \"PV 1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Ltd"))
                .andExpect(jsonPath("$.registrationNumber").value("PV 1"));
        updateCompany(companyAdmin, "{\"name\": \"Renamed Ltd\", \"registrationNumber\": \" \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationNumber").doesNotExist());

        // Two edits: name and number set, then the number cleared. An unchanged name is not recorded.
        // Events of one test transaction share a timestamp, so they are matched rather than ordered.
        accessChanges(companyAdmin)
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("COMPANY_UPDATED"))))
                .andExpect(jsonPath("$[?(@.field == 'name')].to").value("Renamed Ltd"))
                .andExpect(jsonPath("$[?(@.field == 'registrationNumber' && @.from == 'PV 1')].to")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void shouldRefuseCompanyEditsFromOtherRolesAndInvalidNames() throws Exception {
        updateCompany(financeController, "{\"name\": \"Renamed Ltd\"}").andExpect(status().isForbidden());
        updateCompany(companyAdmin, "{\"name\": \" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM companies WHERE id = ?", String.class, companyId))
                .startsWith("Test Company");
    }

    // Access changes

    @Test
    void shouldListAccessChangesNewestFirstWithNames() throws Exception {
        String email = newEmail();
        create(companyAdmin, body(email, "New Person", "CASHIER", PASSWORD)).andExpect(status().isCreated());
        UUID membership = jdbcTemplate.queryForObject(
                "SELECT id FROM company_memberships WHERE company_id = ? AND user_id = ?",
                UUID.class, companyId, userIdOf(email));
        updateMembership(membership, "{\"companyRole\": \"ACCOUNTANT\", \"status\": \"SUSPENDED\"}")
                .andExpect(status().isOk());
        // Setting the current role again is not an access change.
        updateMembership(membership, "{\"companyRole\": \"ACCOUNTANT\"}").andExpect(status().isOk());

        accessChanges(companyAdmin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "MEMBERSHIP_CREATED", "MEMBERSHIP_ROLE_CHANGED", "MEMBERSHIP_STATUS_CHANGED")))
                .andExpect(jsonPath("$[?(@.action == 'MEMBERSHIP_ROLE_CHANGED')].from").value("CASHIER"))
                .andExpect(jsonPath("$[?(@.action == 'MEMBERSHIP_ROLE_CHANGED')].to").value("ACCOUNTANT"))
                .andExpect(jsonPath("$[?(@.action == 'MEMBERSHIP_STATUS_CHANGED')].to").value("SUSPENDED"))
                .andExpect(jsonPath("$[?(@.action == 'MEMBERSHIP_CREATED')].targetName").value("New Person"))
                .andExpect(jsonPath("$[?(@.action == 'MEMBERSHIP_CREATED')].actorName").value("Test User"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity_audit_events WHERE company_id = ? AND details::text LIKE ?",
                Integer.class, companyId, "%" + PASSWORD + "%")).isZero();
    }

    @Test
    void shouldShowAccessChangesToCompanyAdministrationOnly() throws Exception {
        accessChanges(financeController).andExpect(status().isForbidden());
        accessChanges(identities.systemAdmin()).andExpect(status().isOk());
    }

    private ResultActions create(UUID actor, String body) throws Exception {
        return mockMvc.perform(post(companyUrl() + "/memberships")
                .header(HttpHeaders.AUTHORIZATION, identities.bearerToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions updateMembership(UUID membershipId, String body) throws Exception {
        return mockMvc.perform(patch(companyUrl() + "/memberships/" + membershipId)
                .header(HttpHeaders.AUTHORIZATION, identities.bearerToken(companyAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions getCompany(UUID actor) throws Exception {
        return mockMvc.perform(get(companyUrl()).header(HttpHeaders.AUTHORIZATION, identities.bearerToken(actor)));
    }

    private ResultActions updateCompany(UUID actor, String body) throws Exception {
        return mockMvc.perform(patch(companyUrl())
                .header(HttpHeaders.AUTHORIZATION, identities.bearerToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions accessChanges(UUID actor) throws Exception {
        return mockMvc.perform(get(companyUrl() + "/access-changes")
                .header(HttpHeaders.AUTHORIZATION, identities.bearerToken(actor)));
    }

    private String companyUrl() {
        return "/api/v1/identity/companies/" + companyId;
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private static String newEmail() {
        return "new-" + UUID.randomUUID() + "@example.com";
    }

    private static String body(String email, String fullName, String role, String password) {
        return "{\"email\": \"" + email + "\", \"fullName\": \"" + fullName + "\", \"companyRole\": \"" + role + "\""
                + (password == null ? "" : ", \"temporaryPassword\": \"" + password + "\"") + "}";
    }
}
