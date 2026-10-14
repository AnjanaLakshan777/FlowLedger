package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.dto.BankAccountResponse;
import com.flowledger.bankintegration.dto.CreateBankAccountRequest;
import com.flowledger.bankintegration.service.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-integration/companies/{companyId}/bank-accounts")
@RequiredArgsConstructor
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankAccountResponse createBankAccount(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID companyId,
                                                 @Valid @RequestBody CreateBankAccountRequest request) {
        return bankAccountService.createBankAccount(userId(jwt), companyId, request);
    }

    @GetMapping
    public List<BankAccountResponse> listBankAccounts(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable UUID companyId) {
        return bankAccountService.listBankAccounts(userId(jwt), companyId);
    }

    @GetMapping("/{accountId}")
    public BankAccountResponse getBankAccount(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable UUID companyId,
                                              @PathVariable UUID accountId) {
        return bankAccountService.getBankAccount(userId(jwt), companyId, accountId);
    }

    // The token validator only accepts a UUID subject, so this cannot fail for a verified token.
    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
