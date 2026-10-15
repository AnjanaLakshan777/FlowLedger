package com.flowledger.identity.controller;

import com.flowledger.identity.entity.CompanyRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegistrationControllerIntegrationTest {

    private static final String REGISTRATIONS_URL = "/api/v1/identity/registrations";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID companyId;
    private String email;

    @BeforeEach
    void setUp() {
        companyId = insertCompany("ACTIVE");
        // Random address so the tests never collide with data in the local dev database.
        email = "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void shouldRegisterUserWithHashedPasswordAndInvitedMembership() throws Exception {
        register(email.toUpperCase(), CompanyRole.ACCOUNTANT, companyId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId", notNullValue()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Nimal Perera"))
                .andExpect(jsonPath("$.accountStatus").value("PENDING"))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.companyRole").value("ACCOUNTANT"))
                .andExpect(jsonPath("$.membershipStatus").value("INVITED"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        Map<String, Object> user = jdbcTemplate.queryForMap("SELECT * FROM users WHERE email = ?", email);
        String passwordHash = (String) user.get("password_hash");
        assertThat(passwordHash).isNotEqualTo(PASSWORD).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
        assertThat(user.get("full_name")).isEqualTo("Nimal Perera");
        assertThat(user.get("account_status")).isEqualTo("PENDING");
        assertThat(user.get("platform_role")).isEqualTo("USER");

        Map<String, Object> membership = jdbcTemplate.queryForMap(
                "SELECT * FROM company_memberships WHERE user_id = ?", user.get("id"));
        assertThat(membership.get("company_id")).isEqualTo(companyId);
        assertThat(membership.get("company_role")).isEqualTo("ACCOUNTANT");
        assertThat(membership.get("status")).isEqualTo("INVITED");
        assertThat(membership.get("invited_by")).isNull();
    }

    @Test
    void shouldRejectDuplicateEmailIgnoringCase() throws Exception {
        register(email, CompanyRole.CASHIER, companyId).andExpect(status().isCreated());

        register(email.toUpperCase(), CompanyRole.CASHIER, companyId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An account with this email already exists."));

        assertThat(countUsers(email)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = CompanyRole.class, names = {"COMPANY_ADMIN", "FINANCE_CONTROLLER"})
    void shouldNotAllowSelfRegistrationWithPrivilegedRole(CompanyRole role) throws Exception {
        register(email, role, companyId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value("The " + role + " role cannot be requested during self-registration."));

        assertThat(countUsers(email)).isZero();
    }

    @Test
    void shouldRejectUnknownCompany() throws Exception {
        register(email, CompanyRole.ACCOUNTANT, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Company not found."));

        assertThat(countUsers(email)).isZero();
    }

    @Test
    void shouldRejectCompanyThatIsNotActive() throws Exception {
        UUID suspendedCompanyId = insertCompany("SUSPENDED");

        register(email, CompanyRole.ACCOUNTANT, suspendedCompanyId)
                .andExpect(status().isNotFound());

        assertThat(countUsers(email)).isZero();
    }

    // SYSTEM_ADMIN is a platform role, not a company role, so public registration cannot ask for it.
    @Test
    void shouldNotAcceptSystemAdminAsRequestedRole() throws Exception {
        mockMvc.perform(post(REGISTRATIONS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "fullName": "Nimal Perera", "password": "%s",
                                 "companyId": "%s", "companyRole": "SYSTEM_ADMIN"}
                                """.formatted(email, PASSWORD, companyId)))
                .andExpect(status().isBadRequest());

        assertThat(countUsers(email)).isZero();
    }

    @Test
    void shouldIgnorePlatformRoleSentByClient() throws Exception {
        mockMvc.perform(post(REGISTRATIONS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "fullName": "Nimal Perera", "password": "%s",
                                 "companyId": "%s", "companyRole": "ACCOUNTANT", "platformRole": "SYSTEM_ADMIN"}
                                """.formatted(email, PASSWORD, companyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.platformRole").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT platform_role FROM users WHERE email = ?", String.class, email)).isEqualTo("USER");
    }

    private ResultActions register(String email, CompanyRole role, UUID companyId) throws Exception {
        return mockMvc.perform(post(REGISTRATIONS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "email": "%s",
                          "fullName": "  Nimal Perera  ",
                          "password": "%s",
                          "companyId": "%s",
                          "companyRole": "%s"
                        }
                        """.formatted(email, PASSWORD, companyId, role)));
    }

    private UUID insertCompany(String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO companies (name, status) VALUES (?, ?) RETURNING id",
                UUID.class, "Registration Test Company", status);
    }

    private int countUsers(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email);
    }
}
