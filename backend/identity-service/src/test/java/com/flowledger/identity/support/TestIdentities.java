package com.flowledger.identity.support;

import com.flowledger.identity.repository.UserRepository;
import com.flowledger.identity.service.AccessTokenService;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Creates companies, users and memberships directly in the database and issues real access
 * tokens for them, for integration tests that run inside a rolled-back test transaction.
 * Platform roles can only be set this way: no API grants them.
 */
public class TestIdentities {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final AccessTokenService accessTokenService;
    private final EntityManager entityManager;

    public TestIdentities(JdbcTemplate jdbcTemplate, UserRepository userRepository,
                          AccessTokenService accessTokenService, EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.accessTokenService = accessTokenService;
        this.entityManager = entityManager;
    }

    public UUID company(String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO companies (name, status) VALUES (?, ?) RETURNING id",
                UUID.class, "Test Company " + UUID.randomUUID(), status);
    }

    public UUID user(String accountStatus, String platformRole) {
        // Random address so the tests never collide with data in the local dev database.
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (email, full_name, password_hash, account_status, platform_role)
                        VALUES (?, 'Test User', '{bcrypt}not-used-in-these-tests', ?, ?)
                        RETURNING id
                        """,
                UUID.class, "user-" + UUID.randomUUID() + "@example.com", accountStatus, platformRole);
    }

    public UUID systemAdmin() {
        return user("ACTIVE", "SYSTEM_ADMIN");
    }

    public UUID membership(UUID companyId, UUID userId, String companyRole, String status) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO company_memberships (company_id, user_id, company_role, status)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, companyId, userId, companyRole, status);
    }

    // A user with an active account and an active membership in the company.
    public UUID member(UUID companyId, String companyRole) {
        UUID userId = user("ACTIVE", "USER");
        membership(companyId, userId, companyRole, "ACTIVE");
        return userId;
    }

    public String bearerToken(UUID userId) {
        String token = accessTokenService.issueAccessToken(userRepository.findById(userId).orElseThrow()).accessToken();
        // The test transaction shares one persistence context with the request. Detach the user
        // so later changes made through JDBC are read, as they would be in a new request.
        entityManager.clear();
        return "Bearer " + token;
    }
}
