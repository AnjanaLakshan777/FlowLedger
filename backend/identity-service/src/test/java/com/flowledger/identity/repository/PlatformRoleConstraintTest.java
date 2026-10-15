package com.flowledger.identity.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the V8 platform-role constraint directly in the database. Needs the local PostgreSQL
 * database. Each test is rolled back, and a failing statement is the last one in its test.
 */
@SpringBootTest
@Transactional
class PlatformRoleConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldDefaultNewUsersToPlainUser() {
        UUID id = insertUser(null);

        assertThat(jdbcTemplate.queryForObject("SELECT platform_role FROM users WHERE id = ?", String.class, id))
                .isEqualTo("USER");
    }

    @Test
    void shouldAcceptSystemAdmin() {
        UUID id = insertUser("SYSTEM_ADMIN");

        assertThat(jdbcTemplate.queryForObject("SELECT platform_role FROM users WHERE id = ?", String.class, id))
                .isEqualTo("SYSTEM_ADMIN");
    }

    @Test
    void shouldRejectUnknownPlatformRole() {
        // A company role is not a platform role.
        assertThatThrownBy(() -> insertUser("COMPANY_ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_platform_role");
    }

    private UUID insertUser(String platformRole) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        if (platformRole == null) {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO users (email, full_name, password_hash, account_status)
                    VALUES (?, 'Test User', 'x', 'ACTIVE') RETURNING id
                    """, UUID.class, email);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, full_name, password_hash, account_status, platform_role)
                VALUES (?, 'Test User', 'x', 'ACTIVE', ?) RETURNING id
                """, UUID.class, email, platformRole);
    }
}
