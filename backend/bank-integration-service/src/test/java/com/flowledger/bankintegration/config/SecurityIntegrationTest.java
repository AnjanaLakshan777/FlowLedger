package com.flowledger.bankintegration.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real security chain and the real CompanyAccessVerifier with tokens signed the way
 * identity-service signs them. Needs the local bank-integration PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JwtEncoder identityServiceEncoder;
    private String accountsUrl;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        identityServiceEncoder = encoderFor(JwtConfig.signingKey(jwtProperties.secret()));
        companyId = UUID.randomUUID();
        accountsUrl = "/api/v1/bank-integration/companies/" + companyId + "/bank-accounts";
    }

    @Test
    void shouldAllowHealthCheckWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/bank-integration/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("bank-integration-service"));
    }

    @Test
    void shouldRefuseCompanyAccessForValidTokenUntilMembershipCanBeVerified() throws Exception {
        String token = "Bearer " + token(identityServiceEncoder, UUID.randomUUID().toString(),
                jwtProperties.issuer(), Instant.now());

        mockMvc.perform(get(accountsUrl).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You do not have access to this company."));
        mockMvc.perform(get(accountsUrl + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(accountsUrl).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bankName": "Commercial Bank of Ceylon", "accountName": "ABC Trading (Pvt) Ltd",
                                 "accountType": "CURRENT", "integrationMode": "MANUAL"}
                                """))
                .andExpect(status().isForbidden());

        Integer saved = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bank_accounts WHERE company_id = ?", Integer.class, companyId);
        assertThat(saved).isZero();
    }

    @Test
    void shouldRejectMissingBearerToken() throws Exception {
        mockMvc.perform(get(accountsUrl))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void shouldRejectMalformedBearerToken() throws Exception {
        assertInvalidToken("not-a-jwt");
    }

    @Test
    void shouldRejectTokenSignedWithAnotherKey() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder otherEncoder = encoderFor(JwtConfig.signingKey(Base64.getEncoder().encodeToString(otherKey)));

        assertInvalidToken(token(otherEncoder, UUID.randomUUID().toString(), jwtProperties.issuer(), Instant.now()));
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        assertInvalidToken(token(identityServiceEncoder, UUID.randomUUID().toString(),
                jwtProperties.issuer(), Instant.now().minus(Duration.ofHours(1))));
    }

    @Test
    void shouldRejectTokenFromAnotherIssuer() throws Exception {
        assertInvalidToken(token(identityServiceEncoder, UUID.randomUUID().toString(),
                "someone-else", Instant.now()));
    }

    @Test
    void shouldRejectTokenWhoseSubjectIsNotAUserId() throws Exception {
        assertInvalidToken(token(identityServiceEncoder, "admin", jwtProperties.issuer(), Instant.now()));
    }

    private void assertInvalidToken(String token) throws Exception {
        mockMvc.perform(get(accountsUrl).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    private static JwtEncoder encoderFor(SecretKey key) {
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    private static String token(JwtEncoder encoder, String subject, String issuer, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
