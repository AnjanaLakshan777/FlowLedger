package com.flowledger.identity.controller;

import com.flowledger.identity.repository.UserRepository;
import com.flowledger.identity.service.AccessTokenService;
import com.flowledger.identity.support.TestIdentities;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Company user administration: company admins within their own company, system admins in any
 * company. Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MembershipManagementIntegrationTest {

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
    private UUID otherCompanyId;
    private UUID companyAdmin;
    private UUID invitedUser;
    private UUID invitedMembership;

    @BeforeEach
    void setUp() {
        identities = new TestIdentities(jdbcTemplate, userRepository, accessTokenService, entityManager);
        companyId = identities.company("ACTIVE");
        otherCompanyId = identities.company("ACTIVE");
        companyAdmin = identities.member(companyId, "COMPANY_ADMIN");
        invitedUser = identities.user("ACTIVE", "USER");
        invitedMembership = identities.membership(companyId, invitedUser, "ACCOUNTANT", "INVITED");
    }

    @Test
    void shouldLetCompanyAdminListOwnCompanysMembers() throws Exception {
        list(companyId, companyAdmin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].membershipId").value(hasItem(invitedMembership.toString())))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].platformRole").doesNotExist());
    }

    @Test
    void shouldLetCompanyAdminActivateMemberAndChangeRoles() throws Exception {
        update(companyId, invitedMembership, companyAdmin, "{\"status\": \"ACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.companyRole").value("ACCOUNTANT"));

        update(companyId, invitedMembership, companyAdmin, "{\"companyRole\": \"FINANCE_CONTROLLER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyRole").value("FINANCE_CONTROLLER"));

        update(companyId, invitedMembership, companyAdmin, "{\"companyRole\": \"COMPANY_ADMIN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyRole").value("COMPANY_ADMIN"));

        assertThat(membershipColumn(invitedMembership, "company_role")).isEqualTo("COMPANY_ADMIN");
        assertThat(membershipColumn(invitedMembership, "status")).isEqualTo("ACTIVE");
        // Company roles never touch the platform role.
        assertThat(platformRole(invitedUser)).isEqualTo("USER");
    }

    @Test
    void shouldKeepCompanyAdminOutOfOtherCompanies() throws Exception {
        UUID otherMember = identities.user("ACTIVE", "USER");
        UUID otherMembership = identities.membership(otherCompanyId, otherMember, "CASHIER", "INVITED");

        list(otherCompanyId, companyAdmin).andExpect(status().isForbidden());
        update(otherCompanyId, otherMembership, companyAdmin, "{\"status\": \"ACTIVE\"}")
                .andExpect(status().isForbidden());
        // Another company's membership ID does not resolve under the admin's own company.
        update(companyId, otherMembership, companyAdmin, "{\"status\": \"ACTIVE\"}")
                .andExpect(status().isNotFound());

        assertThat(membershipColumn(otherMembership, "status")).isEqualTo("INVITED");
    }

    @Test
    void shouldLetSystemAdminAdministerAnyCompanyWithoutMembership() throws Exception {
        UUID systemAdmin = identities.systemAdmin();
        UUID otherMember = identities.user("ACTIVE", "USER");
        UUID otherMembership = identities.membership(otherCompanyId, otherMember, "CASHIER", "INVITED");

        list(otherCompanyId, systemAdmin).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        update(otherCompanyId, otherMembership, systemAdmin, "{\"companyRole\": \"COMPANY_ADMIN\", \"status\": \"ACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyRole").value("COMPANY_ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldReturnNotFoundWhenSystemAdminListsUnknownCompany() throws Exception {
        list(UUID.randomUUID(), identities.systemAdmin()).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FINANCE_CONTROLLER", "ACCOUNTANT", "CASHIER"})
    void shouldRefuseMembersWhoAreNotCompanyAdmins(String companyRole) throws Exception {
        UUID member = identities.member(companyId, companyRole);

        list(companyId, member).andExpect(status().isForbidden());
        update(companyId, invitedMembership, member, "{\"status\": \"ACTIVE\"}")
                .andExpect(status().isForbidden());

        assertThat(membershipColumn(invitedMembership, "status")).isEqualTo("INVITED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVITED", "SUSPENDED"})
    void shouldRefuseCompanyAdminWhoseMembershipIsNotActive(String membershipStatus) throws Exception {
        UUID inactiveAdmin = identities.user("ACTIVE", "USER");
        identities.membership(companyId, inactiveAdmin, "COMPANY_ADMIN", membershipStatus);

        list(companyId, inactiveAdmin).andExpect(status().isForbidden());
    }

    @Test
    void shouldRefuseCompanyAdminWhoseAccountIsLocked() throws Exception {
        String token = identities.bearerToken(companyAdmin);
        jdbcTemplate.update("UPDATE users SET account_status = 'LOCKED' WHERE id = ?", companyAdmin);

        mockMvc.perform(get(membershipsUrl(companyId)).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRefuseChangesToOwnMembership() throws Exception {
        UUID ownMembership = membershipOf(companyAdmin);
        identities.member(companyId, "COMPANY_ADMIN"); // a second admin, so this is not the last-admin rule

        update(companyId, ownMembership, companyAdmin, "{\"companyRole\": \"CASHIER\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't change your own membership."));
    }

    @Test
    void shouldProtectTheLastActiveCompanyAdmin() throws Exception {
        UUID systemAdmin = identities.systemAdmin();
        UUID onlyAdminMembership = membershipOf(companyAdmin);

        update(companyId, onlyAdminMembership, systemAdmin, "{\"companyRole\": \"ACCOUNTANT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This is the company's last active company admin."));
        update(companyId, onlyAdminMembership, systemAdmin, "{\"status\": \"SUSPENDED\"}")
                .andExpect(status().isConflict());

        identities.member(companyId, "COMPANY_ADMIN");
        update(companyId, onlyAdminMembership, systemAdmin, "{\"companyRole\": \"ACCOUNTANT\"}")
                .andExpect(status().isOk());
    }

    @Test
    void shouldNotAssignPlatformRolesThroughMembershipApi() throws Exception {
        update(companyId, invitedMembership, companyAdmin, "{\"companyRole\": \"SYSTEM_ADMIN\"}")
                .andExpect(status().isBadRequest());

        assertThat(membershipColumn(invitedMembership, "company_role")).isEqualTo("ACCOUNTANT");
        assertThat(platformRole(invitedUser)).isEqualTo("USER");
    }

    @Test
    void shouldRejectInvalidUpdates() throws Exception {
        update(companyId, invitedMembership, companyAdmin, "{}")
                .andExpect(status().isBadRequest());
        update(companyId, invitedMembership, companyAdmin, "{\"status\": \"INVITED\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(membershipsUrl(companyId))).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(membershipsUrl(companyId) + "/" + invitedMembership)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACTIVE\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(membershipColumn(invitedMembership, "status")).isEqualTo("INVITED");
    }

    private ResultActions list(UUID companyId, UUID actor) throws Exception {
        return mockMvc.perform(get(membershipsUrl(companyId))
                .header(HttpHeaders.AUTHORIZATION, identities.bearerToken(actor)));
    }

    private ResultActions update(UUID companyId, UUID membershipId, UUID actor, String body) throws Exception {
        return mockMvc.perform(patch(membershipsUrl(companyId) + "/" + membershipId)
                .header(HttpHeaders.AUTHORIZATION, identities.bearerToken(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String membershipsUrl(UUID companyId) {
        return "/api/v1/identity/companies/" + companyId + "/memberships";
    }

    private UUID membershipOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM company_memberships WHERE company_id = ? AND user_id = ?", UUID.class, companyId, userId);
    }

    private String membershipColumn(UUID membershipId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM company_memberships WHERE id = ?", String.class, membershipId);
    }

    private String platformRole(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT platform_role FROM users WHERE id = ?", String.class, userId);
    }
}
