package com.flowledger.forecast.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flowledger.forecast.exception.BankIntegrationUnavailableException;
import com.flowledger.forecast.exception.NoActiveCurrentAccountException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reads forecast inputs from bank-integration-service, which owns bank accounts, balances,
 * statements and transactions. The user's own token is forwarded, so bank-integration-service
 * applies its own authorization. Any failure or incomplete answer is refused (503), never
 * replaced by empty or zero data.
 */
@Slf4j
@Component
public class BankIntegrationClient {

    private static final String COMPANY_PATH = "/api/v1/bank-integration/companies/{companyId}";

    private final RestClient restClient;

    public BankIntegrationClient(@Qualifier("bankIntegrationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public UUID findActiveCurrentAccount(Jwt user, UUID companyId) {
        List<BankAccount> accounts = call("Bank account lookup", () -> restClient.get()
                .uri(COMPANY_PATH + "/bank-accounts", companyId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .retrieve()
                .body(new ParameterizedTypeReference<List<BankAccount>>() {
                }));

        return accounts.stream()
                .filter(account -> "CURRENT".equals(account.accountType()) && "ACTIVE".equals(account.status()))
                .map(BankAccount::id)
                .findFirst()
                .orElseThrow(NoActiveCurrentAccountException::new);
    }

    // Empty when the account has no confirmed balance yet (404 from bank-integration-service).
    public Optional<Balance> findLatestBalance(Jwt user, UUID companyId, UUID bankAccountId) {
        try {
            Balance balance = call("Balance lookup", () -> restClient.get()
                    .uri(COMPANY_PATH + "/bank-accounts/{bankAccountId}/balance-snapshots/latest",
                            companyId, bankAccountId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                    .retrieve()
                    .body(Balance.class));
            if (balance.balanceAmount() == null || balance.balanceAsOf() == null || balance.currency() == null) {
                log.warn("Balance lookup in bank-integration-service returned an incomplete balance");
                throw new BankIntegrationUnavailableException();
            }
            return Optional.of(balance);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public List<StatementImport> listStatementImports(Jwt user, UUID companyId) {
        List<StatementImport> imports = call("Statement import lookup", () -> restClient.get()
                .uri(COMPANY_PATH + "/statement-imports", companyId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .retrieve()
                .body(new ParameterizedTypeReference<List<StatementImport>>() {
                }));
        if (imports.stream().anyMatch(batch -> batch.bankAccountId() == null || batch.status() == null)) {
            log.warn("Statement import lookup in bank-integration-service returned incomplete batches");
            throw new BankIntegrationUnavailableException();
        }
        return imports;
    }

    public List<Transaction> listTransactions(Jwt user, UUID companyId, UUID bankAccountId,
                                              LocalDate from, LocalDate to) {
        List<Transaction> transactions = call("Transaction lookup", () -> restClient.get()
                .uri(uri -> uri.path(COMPANY_PATH + "/bank-transactions")
                        .queryParam("bankAccountId", bankAccountId)
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build(companyId))
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Transaction>>() {
                }));
        if (transactions.stream().anyMatch(Transaction::invalid)) {
            log.warn("Transaction lookup in bank-integration-service returned invalid transactions");
            throw new BankIntegrationUnavailableException();
        }
        // Only what was asked for, even if the source answered with more.
        return transactions.stream()
                .filter(transaction -> transaction.bankAccountId().equals(bankAccountId))
                .filter(transaction -> !transaction.transactionDate().isBefore(from)
                        && !transaction.transactionDate().isAfter(to))
                .toList();
    }

    private static <T> T call(String what, Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("{} in bank-integration-service failed: {}", what, e.getMessage());
            throw new BankIntegrationUnavailableException();
        }
        if (body == null) {
            log.warn("{} in bank-integration-service returned no body", what);
            throw new BankIntegrationUnavailableException();
        }
        return body;
    }

    private static String bearer(Jwt user) {
        return "Bearer " + user.getTokenValue();
    }

    // Only the fields needed here; the rest of each response is ignored.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BankAccount(UUID id, String accountType, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Balance(UUID bankAccountId, BigDecimal balanceAmount, String currency, Instant balanceAsOf) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatementImport(UUID id, UUID bankAccountId, String status, LocalDate periodStart,
                                  LocalDate periodEnd) {

        public boolean imported() {
            return "IMPORTED".equals(status) && periodStart != null && periodEnd != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transaction(UUID id, UUID bankAccountId, LocalDate transactionDate, String direction,
                              BigDecimal amount, String currency, BigDecimal runningBalance, String category) {

        boolean invalid() {
            return id == null || bankAccountId == null || transactionDate == null || direction == null
                    || amount == null || amount.signum() <= 0 || category == null;
        }
    }
}
