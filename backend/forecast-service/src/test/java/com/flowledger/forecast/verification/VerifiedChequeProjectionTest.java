package com.flowledger.forecast.verification;

import com.flowledger.forecast.exception.ChequeDataNotVerifiedException;
import com.flowledger.forecast.verification.ChequeProjectionVerifier.CompanyResult;
import com.flowledger.forecast.verification.ChequeProjectionVerifier.Reason;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * When the stored verification is relied on and when the company is checked again.
 */
class VerifiedChequeProjectionTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 10, 15);
    private static final LocalDate TO = LocalDate.of(2027, 1, 6);

    private final ChequeVerificationStore store = mock(ChequeVerificationStore.class);
    private final ChequeProjectionVerifier verifier = mock(ChequeProjectionVerifier.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void shouldAlwaysCheckAgainWithAZeroMaximumAge() {
        when(verifier.verifyCompanyNow(COMPANY)).thenReturn(result(Reason.MATCHED));

        projection(Duration.ZERO).issuedCheques(COMPANY, UUID.randomUUID(), FROM, TO);

        verifyNoInteractions(store);
        verify(verifier).verifyCompanyNow(COMPANY);
    }

    @Test
    void shouldUseAStoredVerificationWithinTheMaximumAge() {
        when(store.latest(COMPANY, Duration.ofMinutes(5)))
                .thenReturn(Optional.of(new ChequeVerificationStore.Latest("VERIFIED", "MATCHED", true)));

        projection(Duration.ofMinutes(5)).issuedCheques(COMPANY, UUID.randomUUID(), FROM, TO);

        verify(verifier, never()).verifyCompanyNow(any());
    }

    @Test
    void shouldCheckAgainWhenTheStoredVerificationIsOlder() {
        when(store.latest(COMPANY, Duration.ofMinutes(5)))
                .thenReturn(Optional.of(new ChequeVerificationStore.Latest("VERIFIED", "MATCHED", false)));
        when(verifier.verifyCompanyNow(COMPANY)).thenReturn(result(Reason.MATCHED));

        projection(Duration.ofMinutes(5)).issuedCheques(COMPANY, UUID.randomUUID(), FROM, TO);

        verify(verifier).verifyCompanyNow(COMPANY);
    }

    @Test
    void shouldRefuseWithoutReadingChequesWhenTheCheckDoesNotVerify() {
        when(store.latest(COMPANY, Duration.ofMinutes(5))).thenReturn(Optional.empty());
        when(verifier.verifyCompanyNow(COMPANY)).thenReturn(result(Reason.PROJECTION_BEHIND));

        assertThatThrownBy(() -> projection(Duration.ofMinutes(5)).issuedCheques(COMPANY, UUID.randomUUID(), FROM, TO))
                .isInstanceOf(ChequeDataNotVerifiedException.class)
                .satisfies(e -> assertThat(((ChequeDataNotVerifiedException) e).getBody().getProperties())
                        .containsEntry("chequeVerification", "PROJECTION_BEHIND"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldRejectANegativeMaximumAge() {
        assertThatThrownBy(() -> new ChequeVerificationProperties(3, Duration.ofSeconds(5), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VerifiedChequeProjection projection(Duration maxAge) {
        return new VerifiedChequeProjection(store, verifier, jdbcTemplate,
                new ChequeVerificationProperties(3, Duration.ofSeconds(5), maxAge));
    }

    private static CompanyResult result(Reason reason) {
        return new CompanyResult(COMPANY, reason.status(), reason, ChequeVerificationStore.Figures.NONE,
                ChequeVerificationStore.Figures.NONE);
    }
}
