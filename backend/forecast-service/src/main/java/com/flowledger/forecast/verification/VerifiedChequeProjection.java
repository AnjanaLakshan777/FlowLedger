package com.flowledger.forecast.verification;

import com.flowledger.forecast.calculation.ExpectedCheque;
import com.flowledger.forecast.exception.ChequeDataNotVerifiedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The forecast's source of cheques: forecast_cheques, used only while the company's projection is
 * VERIFIED. A stored VERIFIED result is used if it is at most
 * {@code flowledger.cheque-verification.max-age} old (database time); otherwise, or if the stored
 * result is anything else or missing, the company is checked once more on the spot. If that check
 * is not VERIFIED either, the forecast is refused (503) instead of being calculated from a
 * projection that may be incomplete.
 */
@Slf4j
@Component
public class VerifiedChequeProjection {

    private final ChequeVerificationStore store;
    private final ChequeProjectionVerifier verifier;
    private final JdbcTemplate jdbcTemplate;
    private final Duration maxAge;

    public VerifiedChequeProjection(ChequeVerificationStore store, ChequeProjectionVerifier verifier,
                                    JdbcTemplate jdbcTemplate, ChequeVerificationProperties properties) {
        this.store = store;
        this.verifier = verifier;
        this.jdbcTemplate = jdbcTemplate;
        this.maxAge = properties.maxAge();
    }

    /**
     * ISSUED cheques of the account expected to clear between from and to, inclusive, from a
     * verified projection.
     *
     * @throws ChequeDataNotVerifiedException if the company's projection is not verified now
     */
    public List<ExpectedCheque> issuedCheques(UUID companyId, UUID accountId, LocalDate from, LocalDate to) {
        requireVerified(companyId);
        // Read after the check: the projection only moves towards payment-service's current state.
        return jdbcTemplate.query("""
                        SELECT cheque_id, cheque_number, amount, expected_clearing_date, source_version
                        FROM forecast_cheques
                        WHERE company_id = ?
                          AND bank_account_id = ?
                          AND status = 'ISSUED'
                          AND expected_clearing_date BETWEEN ? AND ?
                        """,
                (rs, rowNum) -> new ExpectedCheque(rs.getObject("cheque_id", UUID.class),
                        rs.getString("cheque_number"), rs.getBigDecimal("amount"),
                        rs.getObject("expected_clearing_date", LocalDate.class), rs.getInt("source_version")),
                companyId, accountId, from, to);
    }

    private void requireVerified(UUID companyId) {
        Optional<ChequeVerificationStore.Latest> latest = maxAge.isZero()
                ? Optional.empty() : store.latest(companyId, maxAge);
        if (latest.isPresent() && latest.get().verifiedWithinMaxAge()) {
            return;
        }
        ChequeProjectionVerifier.CompanyResult result = verifier.verifyCompanyNow(companyId);
        if (result.status() != ChequeProjectionVerifier.Status.VERIFIED) {
            log.warn("Forecast for company {} refused: cheque projection {} ({})", companyId, result.status(),
                    result.reason());
            throw new ChequeDataNotVerifiedException(result.reason().name());
        }
    }
}
