package com.flowledger.forecast.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowledger.forecast.calculation.PreviousYearPatternStrategy.RecordedSale;
import com.flowledger.forecast.exception.SalesHistoryUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import com.flowledger.forecast.config.CashManagementProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads recorded sales from cash-management-service, which owns sales and their imports, with the
 * user's own token (or, for the daily scheduled forecast, from the internal API with the internal
 * API key): approved import rows (including days approved with zero sales) and daily sales
 * entered by hand. Any failure or answer that breaks the contract is refused (503), never replaced
 * by an empty history.
 */
@Slf4j
@Component
public class CashManagementClient {

    private static final String HISTORY_PATH = "/api/v1/cash-management/companies/{companyId}/sales-history";
    private static final String INTERNAL_HISTORY_PATH = "/internal/v1/cash-management/companies/{companyId}/sales-history";

    private final RestClient restClient;
    private final String internalApiSecret;

    public CashManagementClient(@Qualifier("cashManagementRestClient") RestClient restClient,
                                CashManagementProperties properties) {
        this.restClient = restClient;
        this.internalApiSecret = properties.internalApiSecret();
    }

    // A user's own token on the public API; the internal API key for the service itself.
    private void authorize(HttpHeaders headers, SourceCaller caller) {
        if (!caller.isService()) {
            headers.set(HttpHeaders.AUTHORIZATION, caller.bearer());
            return;
        }
        if (internalApiSecret == null || internalApiSecret.isBlank()) {
            log.warn("CASH_MANAGEMENT_INTERNAL_API_SECRET is not set; sales history cannot be read without a user");
            throw new SalesHistoryUnavailableException();
        }
        headers.set(BankIntegrationClient.INTERNAL_KEY_HEADER, internalApiSecret);
    }

    /** Recorded sales with a sale date between from and to, inclusive, one per date. */
    public List<RecordedSale> recordedSales(SourceCaller caller, UUID companyId, LocalDate from, LocalDate to) {
        History history;
        try {
            history = restClient.get()
                    .uri(uri -> uri.path(caller.isService() ? INTERNAL_HISTORY_PATH : HISTORY_PATH)
                            .queryParam("from", from).queryParam("to", to).build(companyId))
                    .headers(headers -> authorize(headers, caller))
                    .retrieve()
                    .body(History.class);
        } catch (RestClientException e) {
            log.warn("Sales history lookup in cash-management-service failed: {}", e.getClass().getSimpleName());
            throw new SalesHistoryUnavailableException();
        }
        if (history == null || history.days() == null) {
            log.warn("Sales history lookup in cash-management-service returned no body");
            throw new SalesHistoryUnavailableException();
        }
        Set<LocalDate> dates = new HashSet<>();
        for (Day day : history.days()) {
            if (day == null || day.invalid() || day.saleDate().isBefore(from) || day.saleDate().isAfter(to)
                    || !dates.add(day.saleDate())) {
                log.warn("Sales history lookup in cash-management-service returned an invalid day");
                throw new SalesHistoryUnavailableException();
            }
        }
        return history.days().stream()
                .map(day -> new RecordedSale(day.saleDate(), day.cashSalesAmount(), day.cardSalesAmount(),
                        day.importBatchId()))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record History(List<Day> days) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Day(LocalDate saleDate, BigDecimal cashSalesAmount, BigDecimal cardSalesAmount, UUID importBatchId,
               String entrySource) {

        // An import row names its batch; a manual entry has none.
        boolean invalid() {
            boolean sourceMatches = "HISTORICAL_IMPORT".equals(entrySource) ? importBatchId != null
                    : "UI".equals(entrySource) && importBatchId == null;
            return saleDate == null || !sourceMatches || !money(cashSalesAmount) || !money(cardSalesAmount);
        }

        private static boolean money(BigDecimal amount) {
            return amount != null && amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2;
        }
    }
}
