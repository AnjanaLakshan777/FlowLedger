package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.dto.BankBalanceSnapshotResponse;
import com.flowledger.bankintegration.dto.BankTransactionResponse;
import com.flowledger.bankintegration.dto.ManualBalanceRequest;
import com.flowledger.bankintegration.dto.ManualTransactionRequest;
import com.flowledger.bankintegration.service.ManualEntryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Manual bank entry for an account whose connection is MANUAL.
@RestController
@RequestMapping("/api/v1/bank-integration/companies/{companyId}/bank-accounts/{bankAccountId}")
@RequiredArgsConstructor
public class ManualEntryController {

    private final ManualEntryService manualEntryService;

    @PostMapping("/manual-transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public BankTransactionResponse addTransaction(@AuthenticationPrincipal Jwt user,
                                                  @PathVariable UUID companyId,
                                                  @PathVariable UUID bankAccountId,
                                                  @Valid @RequestBody ManualTransactionRequest request) {
        return manualEntryService.addTransaction(user, companyId, bankAccountId, request);
    }

    @DeleteMapping("/manual-transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@AuthenticationPrincipal Jwt user,
                                  @PathVariable UUID companyId,
                                  @PathVariable UUID bankAccountId,
                                  @PathVariable UUID transactionId) {
        manualEntryService.deleteTransaction(user, companyId, bankAccountId, transactionId);
    }

    @PostMapping("/manual-balances")
    @ResponseStatus(HttpStatus.CREATED)
    public BankBalanceSnapshotResponse recordBalance(@AuthenticationPrincipal Jwt user,
                                                     @PathVariable UUID companyId,
                                                     @PathVariable UUID bankAccountId,
                                                     @Valid @RequestBody ManualBalanceRequest request) {
        return manualEntryService.recordBalance(user, companyId, bankAccountId, request);
    }
}
