package com.flowledger.forecast.controller;

import com.flowledger.forecast.support.FakeIdentityServer;
import com.flowledger.forecast.support.ForecastConfigs;
import com.flowledger.forecast.support.FakePaymentInternalApi;
import com.flowledger.forecast.support.FakeSourceServices;
import com.flowledger.forecast.support.FixedClockConfig;
import com.flowledger.forecast.support.ProjectedCheques;
import com.flowledger.forecast.verification.DeadLetterEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static com.flowledger.forecast.support.ProjectedCheques.cheque;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A forecast uses the cheque projection only while the company's projection is VERIFIED: a stored
 * result up to the maximum age (10 minutes here) is used as is; a missing, older or non-VERIFIED
 * result makes the forecast check the company on the spot against a fake payment-service summary,
 * and a forecast is refused when that check does not verify. Needs the local forecast PostgreSQL
 * database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockConfig.class)
@Transactional
class ForecastChequeVerificationIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();
    private static final FakeSourceServices bank = FakeSourceServices.instance();
    private static final FakePaymentInternalApi payment = FakePaymentInternalApi.instance();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
        registry.add("flowledger.bank-integration.base-url", bank::baseUrl);
        registry.add("flowledger.payment.base-url", payment::baseUrl);
        registry.add("flowledger.cheque-verification.max-age", () -> "10m");
    }

    @Value("${flowledger.payment.internal-api-secret}")
    private String internalKey;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private DeadLetterEvidence deadLetterEvidence;

    private UUID companyId;
    private UUID accountId;
    private UUID chequeId;
    private ProjectedCheques projected;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        chequeId = UUID.randomUUID();
        projected = new ProjectedCheques(jdbcTemplate);
        // These tests cover the WEEKDAY_AVERAGE method; PREVIOUS_YEAR_PATTERN has its own tests.
        ForecastConfigs.weekdayAverage(jdbcTemplate, companyId);
        identityService.reset();
        bank.reset();
        payment.reset(internalKey);
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        bank.currentAccount(companyId, accountId);
        bank.balance(companyId, accountId, "500000.00", "2026-10-14T18:29:59Z");
        bank.statements(companyId, accountId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14));
        bank.transactions(companyId, List.of());
        when(deadLetterEvidence.deadLetterCount()).thenReturn(OptionalLong.of(0));

        projected.cheques(companyId, List.of(
                cheque(chequeId, accountId, "000123", "250000.00", LocalDate.of(2026, 10, 20), "ISSUED")));
        // payment-service agrees with the projection unless a test changes it.
        payment.cheque(chequeId, companyId, "ISSUED", 1);
    }

    @Test
    void shouldUseAFreshStoredVerificationWithoutCheckingAgain() throws Exception {
        payment.reset(internalKey);

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.cheques[*].chequeId").value(contains(chequeId.toString())))
                .andExpect(jsonPath("$.totals.expectedChequeOutflows").value(250000.0));

        assertThat(payment.requestedSummaries()).isEmpty();
    }

    @Test
    void shouldCheckTheCompanyWhenNoVerificationIsStored() throws Exception {
        deleteVerification();

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.expectedChequeOutflows").value(250000.0));

        assertThat(payment.requestedSummaries()).containsExactly(companyId.toString());
        assertThat(verificationStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void shouldRefuseWhenNoVerificationIsStoredAndTheCheckFails() throws Exception {
        deleteVerification();
        payment.failSummary(1);

        refused("SUMMARY_UNAVAILABLE");

        assertThat(verificationStatus()).isEqualTo("RETRY_REQUIRED");
    }

    @Test
    void shouldCheckAgainWhenTheStoredVerificationIsTooOld() throws Exception {
        makeVerificationOlderThanMaxAge();

        create().andExpect(status().isCreated());

        assertThat(payment.requestedSummaries()).containsExactly(companyId.toString());
    }

    @Test
    void shouldRefuseAnOldVerificationThatNoLongerHolds() throws Exception {
        makeVerificationOlderThanMaxAge();
        // A cheque issued since, whose event has not reached the projection.
        payment.cheque(UUID.randomUUID(), companyId, "ISSUED", 1);

        refused("PROJECTION_BEHIND");
    }

    @Test
    void shouldRefuseWhenTheStoredResultIsNotVerifiedAndStillFails() throws Exception {
        storeResult("NOT_VERIFIED", "DEAD_LETTERS_PRESENT");
        when(deadLetterEvidence.deadLetterCount()).thenReturn(OptionalLong.of(2));

        refused("DEAD_LETTERS_PRESENT");
    }

    @Test
    void shouldCheckAgainWhenTheStoredResultIsNotVerified() throws Exception {
        storeResult("RETRY_REQUIRED", "PROJECTION_BEHIND");

        create().andExpect(status().isCreated());

        assertThat(verificationStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void shouldRefuseWhenTheDeadLetterQueueCannotBeChecked() throws Exception {
        deleteVerification();
        when(deadLetterEvidence.deadLetterCount()).thenReturn(OptionalLong.empty());

        refused("DEAD_LETTER_EVIDENCE_UNAVAILABLE");
    }

    @Test
    void shouldRefuseBeforeAnyBackfillHasCompleted() throws Exception {
        assumeTrue(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cheque_backfill_runs WHERE status = 'COMPLETED'", Integer.class) == 1,
                "completed backfill runs from elsewhere");
        deleteVerification();
        jdbcTemplate.update("DELETE FROM cheque_backfill_runs WHERE status = 'COMPLETED'");

        refused("NO_COMPLETED_BACKFILL");
    }

    @Test
    void shouldUseOnlyTheCompanysOwnProjectedCheques() throws Exception {
        UUID otherCompany = UUID.randomUUID();
        projected.cheques(otherCompany, List.of(
                cheque(UUID.randomUUID(), accountId, "000777", "99999.00", LocalDate.of(2026, 10, 21), "ISSUED")));

        create().andExpect(status().isCreated())
                .andExpect(jsonPath("$.cheques[*].chequeNumber").value(contains("000123")))
                .andExpect(jsonPath("$.totals.expectedChequeOutflows").value(250000.0));
    }

    private void refused(String reason) throws Exception {
        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail")
                        .value("Cheque data could not be confirmed as complete. Please try again later."))
                .andExpect(jsonPath("$.chequeVerification").value(reason));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM forecast_runs WHERE company_id = ?",
                Integer.class, companyId)).isZero();
    }

    private void deleteVerification() {
        jdbcTemplate.update("DELETE FROM cheque_projection_verifications WHERE company_id = ?", companyId);
    }

    private void makeVerificationOlderThanMaxAge() {
        jdbcTemplate.update("""
                UPDATE cheque_projection_verifications
                SET verified_at = now() - interval '11 minutes', projection_read_at = now() - interval '11 minutes'
                WHERE company_id = ?
                """, companyId);
    }

    private void storeResult(String status, String reason) {
        jdbcTemplate.update("""
                UPDATE cheque_projection_verifications SET status = ?, reason = ?, verified_at = NULL
                WHERE company_id = ?
                """, status, reason, companyId);
    }

    private String verificationStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM cheque_projection_verifications WHERE company_id = ?", String.class, companyId);
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post("/api/v1/forecast/companies/" + companyId + "/forecast-runs")
                .with(jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()))));
    }
}
