package com.flowledger.identity.controller;

import com.flowledger.identity.repository.UserRepository;
import com.flowledger.identity.service.AccessTokenService;
import com.flowledger.identity.support.TestIdentities;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating companies is a platform-wide operation reserved for system admins.
 * Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CompanyControllerIntegrationTest {

    private static final String COMPANIES_URL = "/api/v1/identity/companies";
    private static final String VALID_REQUEST = """
            {"name": "Colombo Spices (Pvt) Ltd"}
            """;

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
    private String systemAdminToken;

    @BeforeEach
    void setUp() {
        identities = new TestIdentities(jdbcTemplate, userRepository, accessTokenService, entityManager);
        systemAdminToken = identities.bearerToken(identities.systemAdmin());
    }

    @Test
    void shouldPersistCompanyWithDatabaseGeneratedValues() throws Exception {
        MvcResult result = createCompany(systemAdminToken, """
                {"name": "  ABC Trading (Pvt) Ltd  ", "registrationNumber": "PV12345"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("ABC Trading (Pvt) Ltd"))
                .andExpect(jsonPath("$.registrationNumber").value("PV12345"))
                .andExpect(jsonPath("$.defaultCurrency").value("LKR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Colombo"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andReturn();

        UUID id = UUID.fromString(JsonPath.read(
                result.getResponse().getContentAsString(), "$.id"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM companies WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("ABC Trading (Pvt) Ltd");
        assertThat(row.get("registration_number")).isEqualTo("PV12345");
        assertThat(row.get("default_currency")).isEqualTo("LKR");
        assertThat(row.get("timezone")).isEqualTo("Asia/Colombo");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void shouldCreateCompanyWithoutRegistrationNumber() throws Exception {
        createCompany(systemAdminToken, VALID_REQUEST)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.registrationNumber", nullValue()));
    }

    @Test
    void shouldRejectInvalidRequestWithoutSaving() throws Exception {
        int before = countCompanies();

        createCompany(systemAdminToken, """
                {"name": ""}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));

        assertThat(countCompanies()).isEqualTo(before);
    }

    @Test
    void shouldRejectAnonymousRequest() throws Exception {
        int before = countCompanies();

        mockMvc.perform(post(COMPANIES_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());

        assertThat(countCompanies()).isEqualTo(before);
    }

    // A company admin administers only their own company; creating companies is platform-wide.
    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT", "CASHIER"})
    void shouldRefuseCompanyMembersIncludingCompanyAdmins(String companyRole) throws Exception {
        UUID member = identities.member(identities.company("ACTIVE"), companyRole);
        int before = countCompanies();

        createCompany(identities.bearerToken(member), VALID_REQUEST)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This operation requires a system admin."));

        assertThat(countCompanies()).isEqualTo(before);
    }

    @Test
    void shouldRefuseSystemAdminWhoseAccountIsNoLongerActive() throws Exception {
        UUID systemAdmin = identities.systemAdmin();
        String token = identities.bearerToken(systemAdmin);
        jdbcTemplate.update("UPDATE users SET account_status = 'LOCKED' WHERE id = ?", systemAdmin);

        createCompany(token, VALID_REQUEST).andExpect(status().isForbidden());
    }

    private ResultActions createCompany(String bearerToken, String body) throws Exception {
        return mockMvc.perform(post(COMPANIES_URL)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int countCompanies() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Integer.class);
    }
}
