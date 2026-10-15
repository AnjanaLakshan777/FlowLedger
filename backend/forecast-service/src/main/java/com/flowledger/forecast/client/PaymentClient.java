package com.flowledger.forecast.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowledger.forecast.exception.PaymentServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reads issued cheques from payment-service with the user's own token. If payment-service cannot
 * answer, the forecast is refused (503): running it without cheques would overstate the balance.
 */
@Slf4j
@Component
public class PaymentClient {

    private static final String CHEQUES_PATH = "/api/v1/payment/companies/{companyId}/cheques";

    private final RestClient restClient;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // ISSUED cheques expected to clear between from and to, inclusive.
    public List<Cheque> listIssuedCheques(Jwt user, UUID companyId, LocalDate from, LocalDate to) {
        List<Cheque> cheques;
        try {
            cheques = restClient.get()
                    .uri(uri -> uri.path(CHEQUES_PATH)
                            .queryParam("status", "ISSUED")
                            .queryParam("clearingFrom", from)
                            .queryParam("clearingTo", to)
                            .build(companyId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.getTokenValue())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientException e) {
            log.warn("Cheque lookup in payment-service failed: {}", e.getMessage());
            throw new PaymentServiceUnavailableException();
        }
        if (cheques == null || cheques.stream().anyMatch(Cheque::invalid)) {
            log.warn("Cheque lookup in payment-service returned no body or invalid cheques");
            throw new PaymentServiceUnavailableException();
        }
        // Cancelled cheques never count, whatever the source returned.
        return cheques.stream()
                .filter(cheque -> "ISSUED".equals(cheque.status()))
                .filter(cheque -> !cheque.expectedClearingDate().isBefore(from)
                        && !cheque.expectedClearingDate().isAfter(to))
                .toList();
    }

    // Only the fields needed here; payee, notes and the rest are ignored.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cheque(UUID id, UUID bankAccountId, String chequeNumber, BigDecimal amount,
                         LocalDate expectedClearingDate, String status, Integer version) {

        boolean invalid() {
            return id == null || bankAccountId == null || chequeNumber == null || amount == null
                    || amount.signum() <= 0 || expectedClearingDate == null || status == null || version == null;
        }
    }
}
