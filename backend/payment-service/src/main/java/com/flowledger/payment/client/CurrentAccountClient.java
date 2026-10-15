package com.flowledger.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowledger.payment.exception.BankIntegrationUnavailableException;
import com.flowledger.payment.exception.NoActiveCurrentAccountException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

/**
 * Finds the company's active current account in bank-integration-service, which owns bank
 * accounts. The MVP has one current account per company, and cheques are drawn on it. The user's
 * own token is forwarded, so bank-integration-service applies its own authorization.
 */
@Slf4j
@Component
public class CurrentAccountClient {

    private static final String ACCOUNTS_PATH = "/api/v1/bank-integration/companies/{companyId}/bank-accounts";

    private final RestClient bankIntegrationRestClient;

    public CurrentAccountClient(@Qualifier("bankIntegrationRestClient") RestClient bankIntegrationRestClient) {
        this.bankIntegrationRestClient = bankIntegrationRestClient;
    }

    public UUID findActiveCurrentAccount(Jwt user, UUID companyId) {
        List<BankAccountSummary> accounts;
        try {
            accounts = bankIntegrationRestClient.get()
                    .uri(ACCOUNTS_PATH, companyId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.getTokenValue())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientException e) {
            log.warn("Current account lookup in bank-integration-service failed: {}", e.getMessage());
            throw new BankIntegrationUnavailableException();
        }
        if (accounts == null) {
            log.warn("Current account lookup in bank-integration-service returned no body");
            throw new BankIntegrationUnavailableException();
        }

        return accounts.stream()
                .filter(account -> "CURRENT".equals(account.accountType()) && "ACTIVE".equals(account.status()))
                .map(BankAccountSummary::id)
                .findFirst()
                .orElseThrow(NoActiveCurrentAccountException::new);
    }

    // Only the fields needed here; the rest of bank-integration-service's response is ignored.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BankAccountSummary(UUID id, String accountType, String status) {
    }
}
