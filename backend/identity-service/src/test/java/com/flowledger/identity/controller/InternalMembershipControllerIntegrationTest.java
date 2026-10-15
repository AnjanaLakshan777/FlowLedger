package com.flowledger.identity.controller;

import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.repository.UserRepository;
import com.flowledger.identity.service.AccessTokenService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalMembershipControllerIntegrationTest {

    private static final String SERVICE_KEY_HEADER = "X-Internal-Api-Key";

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

    @Value("${flowledger.security.internal-api.secret}")
    private String internalApiSecret;

    private UUID companyId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        companyId = insertCompany("ACTIVE");
        userId = insertUser("ACTIVE");
    }

    @ParameterizedTest
    @EnumSource(CompanyRole.class)
    void shouldAllowActiveMemberAndReturnRole(CompanyRole role) throws Exception {
        insertMembership(companyId, userId, role.name(), "ACTIVE");

        checkMembership(companyId, accessToken(userId), internalApiSecret)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.companyRole").value(role.name()))
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void shouldReportActiveSystemAdminWithoutMembershipAsPlatformAdmin() throws Exception {
        makeSystemAdmin(userId);

        checkMembership(companyId, accessToken(userId), internalApiSecret)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.companyRole", nullValue()));
    }

    @Test
    void shouldReportBothRolesForSystemAdminWhoIsAlsoAMember() throws Exception {
        makeSystemAdmin(userId);
        insertMembership(companyId, userId, "ACCOUNTANT", "ACTIVE");

        checkMembership(companyId, accessToken(userId), internalApiSecret)
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.companyRole").value("ACCOUNTANT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "LOCKED", "DISABLED"})
    void shouldDenySystemAdminWhoseAccountIsNotActive(String accountStatus) throws Exception {
        makeSystemAdmin(userId);
        String token = accessToken(userId);
        jdbcTemplate.update("UPDATE users SET account_status = ? WHERE id = ?", accountStatus, userId);

        assertDenied(checkMembership(companyId, token, internalApiSecret));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "CLOSED"})
    void shouldDenySystemAdminForCompanyThatIsNotActive(String companyStatus) throws Exception {
        makeSystemAdmin(userId);
        jdbcTemplate.update("UPDATE companies SET status = ? WHERE id = ?", companyStatus, companyId);

        assertDenied(checkMembership(companyId, accessToken(userId), internalApiSecret));
    }

    @Test
    void shouldDenySystemAdminForUnknownCompany() throws Exception {
        makeSystemAdmin(userId);

        assertDenied(checkMembership(UUID.randomUUID(), accessToken(userId), internalApiSecret));
    }

    @Test
    void shouldNotTreatCompanyAdminAsPlatformAdminInAnotherCompany() throws Exception {
        UUID otherCompanyId = insertCompany("ACTIVE");
        insertMembership(otherCompanyId, userId, "COMPANY_ADMIN", "ACTIVE");

        assertDenied(checkMembership(companyId, accessToken(userId), internalApiSecret));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "LOCKED", "DISABLED"})
    void shouldDenyUserWhoseAccountIsNotActive(String accountStatus) throws Exception {
        insertMembership(companyId, userId, "COMPANY_ADMIN", "ACTIVE");
        // The token was issued while the account was active; the check must use the current status.
        String token = accessToken(userId);
        jdbcTemplate.update("UPDATE users SET account_status = ? WHERE id = ?", accountStatus, userId);

        assertDenied(checkMembership(companyId, token, internalApiSecret));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "CLOSED"})
    void shouldDenyCompanyThatIsNotActive(String companyStatus) throws Exception {
        insertMembership(companyId, userId, "COMPANY_ADMIN", "ACTIVE");
        jdbcTemplate.update("UPDATE companies SET status = ? WHERE id = ?", companyStatus, companyId);

        assertDenied(checkMembership(companyId, accessToken(userId), internalApiSecret));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVITED", "SUSPENDED"})
    void shouldDenyMembershipThatIsNotActive(String membershipStatus) throws Exception {
        insertMembership(companyId, userId, "COMPANY_ADMIN", membershipStatus);

        assertDenied(checkMembership(companyId, accessToken(userId), internalApiSecret));
    }

    @Test
    void shouldDenyUserWithoutMembership() throws Exception {
        assertDenied(checkMembership(companyId, accessToken(userId), internalApiSecret));
    }

    @Test
    void shouldDenyMembershipOfAnotherCompany() throws Exception {
        UUID otherCompanyId = insertCompany("ACTIVE");
        insertMembership(otherCompanyId, userId, "COMPANY_ADMIN", "ACTIVE");

        assertDenied(checkMembership(companyId, accessToken(userId), internalApiSecret));
    }

    @Test
    void shouldAnswerOnlyForTheUserInTheAccessToken() throws Exception {
        UUID memberId = insertUser("ACTIVE");
        insertMembership(companyId, memberId, "COMPANY_ADMIN", "ACTIVE");

        // userId has no membership; the member's ID cannot be substituted through the request.
        assertDenied(mockMvc.perform(get(membershipUrl(companyId) + "?userId=" + memberId)
                .header(SERVICE_KEY_HEADER, internalApiSecret)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(userId))));
    }

    @Test
    void shouldRejectMissingServiceKey() throws Exception {
        insertMembership(companyId, userId, "COMPANY_ADMIN", "ACTIVE");

        mockMvc.perform(get(membershipUrl(companyId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(userId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid service credentials."))
                .andExpect(jsonPath("$.allowed").doesNotExist());
    }

    @Test
    void shouldRejectWrongServiceKey() throws Exception {
        insertMembership(companyId, userId, "COMPANY_ADMIN", "ACTIVE");

        checkMembership(companyId, accessToken(userId), internalApiSecret + "x")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.allowed").doesNotExist());
    }

    @Test
    void shouldRejectMissingUserToken() throws Exception {
        mockMvc.perform(get(membershipUrl(companyId)).header(SERVICE_KEY_HEADER, internalApiSecret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidUserToken() throws Exception {
        checkMembership(companyId, accessToken(userId) + "x", internalApiSecret)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotBeReachableWithUserTokenAlone() throws Exception {
        insertMembership(companyId, userId, "COMPANY_ADMIN", "ACTIVE");

        mockMvc.perform(get(membershipUrl(companyId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(userId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotChangeAnyDataWhenChecking() throws Exception {
        insertMembership(companyId, userId, "ACCOUNTANT", "ACTIVE");
        String token = accessToken(userId);
        List<Map<String, Object>> before = snapshot();

        checkMembership(companyId, token, internalApiSecret).andExpect(status().isOk());
        checkMembership(UUID.randomUUID(), token, internalApiSecret).andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(before);
    }

    private ResultActions checkMembership(UUID companyId, String accessToken, String serviceKey) throws Exception {
        return mockMvc.perform(get(membershipUrl(companyId))
                .header(SERVICE_KEY_HEADER, serviceKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private static void assertDenied(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.companyRole", nullValue()))
                .andExpect(jsonPath("$.platformAdmin").value(false));
    }

    // Platform roles are only ever set directly in the database.
    private void makeSystemAdmin(UUID userId) {
        jdbcTemplate.update("UPDATE users SET platform_role = 'SYSTEM_ADMIN' WHERE id = ?", userId);
    }

    private static String membershipUrl(UUID companyId) {
        return "/internal/v1/identity/companies/" + companyId + "/membership";
    }

    private String accessToken(UUID userId) {
        String token = accessTokenService.issueAccessToken(userRepository.findById(userId).orElseThrow()).accessToken();
        // The test transaction shares one persistence context with the request. Detach the user
        // so later status changes made through JDBC are read, as they would be in a new request.
        entityManager.clear();
        return token;
    }

    private List<Map<String, Object>> snapshot() {
        return jdbcTemplate.queryForList("""
                SELECT u.account_status, u.updated_at AS user_updated_at, u.last_login_at,
                       c.status AS company_status, c.updated_at AS company_updated_at,
                       m.company_role, m.status AS membership_status, m.updated_at AS membership_updated_at
                FROM company_memberships m
                JOIN users u ON u.id = m.user_id
                JOIN companies c ON c.id = m.company_id
                WHERE m.user_id = ?
                """, userId);
    }

    private UUID insertCompany(String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO companies (name, status) VALUES (?, ?) RETURNING id",
                UUID.class, "Membership Test Company", status);
    }

    private UUID insertUser(String accountStatus) {
        // Random address so the tests never collide with data in the local dev database.
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (email, full_name, password_hash, account_status)
                        VALUES (?, 'Nimal Perera', '{bcrypt}not-used-in-these-tests', ?)
                        RETURNING id
                        """,
                UUID.class, "user-" + UUID.randomUUID() + "@example.com", accountStatus);
    }

    private void insertMembership(UUID companyId, UUID userId, String role, String status) {
        jdbcTemplate.update("""
                INSERT INTO company_memberships (company_id, user_id, company_role, status)
                VALUES (?, ?, ?, ?)
                """, companyId, userId, role, status);
    }
}
