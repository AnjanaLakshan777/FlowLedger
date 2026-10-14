package com.flowledger.identity.controller;

import com.flowledger.identity.entity.AccountStatus;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerIntegrationTest {

    private static final String LOGIN_URL = "/api/v1/identity/auth/login";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String INVALID_CREDENTIALS = "Invalid email or password.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    private String email;

    @BeforeEach
    void setUp() {
        // Random address so the tests never collide with data in the local dev database.
        email = "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void shouldIssueAccessTokenForActiveUser() throws Exception {
        UUID userId = insertUser(email, AccountStatus.ACTIVE);

        String body = login(email.toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("flowledger-identity");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "iss", "iat", "exp");
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        insertUser(email, AccountStatus.ACTIVE);

        login(email, "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void shouldRejectUnknownEmailWithSameResponseAsWrongPassword() throws Exception {
        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"PENDING", "LOCKED", "DISABLED"})
    void shouldRejectAccountThatIsNotActiveEvenWithCorrectPassword(AccountStatus status) throws Exception {
        insertUser(email, status);

        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void shouldRejectBlankCredentials() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("email must not be blank"))
                .andExpect(jsonPath("$.errors.password").value("password must not be blank"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private UUID insertUser(String email, AccountStatus status) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (email, full_name, password_hash, account_status)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class, email, "Nimal Perera", passwordEncoder.encode(PASSWORD), status.name());
    }
}
