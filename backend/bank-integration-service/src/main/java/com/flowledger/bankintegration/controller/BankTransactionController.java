package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.dto.BankTransactionResponse;
import com.flowledger.bankintegration.dto.ClassifyTransactionRequest;
import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.service.BankTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-integration/companies/{companyId}/bank-transactions")
@RequiredArgsConstructor
public class BankTransactionController {

    private final BankTransactionService bankTransactionService;

    // from and to filter on the actual bank transaction date.
    @GetMapping
    public List<BankTransactionResponse> listTransactions(
            @AuthenticationPrincipal Jwt user,
            @PathVariable UUID companyId,
            @RequestParam(required = false) UUID bankAccountId,
            @RequestParam(required = false) TransactionCategory category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return bankTransactionService.listTransactions(user, companyId, bankAccountId, category, from, to);
    }

    @GetMapping("/{transactionId}")
    public BankTransactionResponse getTransaction(@AuthenticationPrincipal Jwt user,
                                                  @PathVariable UUID companyId,
                                                  @PathVariable UUID transactionId) {
        return bankTransactionService.getTransaction(user, companyId, transactionId);
    }

    @PutMapping("/{transactionId}/classification")
    public BankTransactionResponse classify(@AuthenticationPrincipal Jwt user,
                                            @PathVariable UUID companyId,
                                            @PathVariable UUID transactionId,
                                            @Valid @RequestBody ClassifyTransactionRequest request) {
        return bankTransactionService.classify(user, companyId, transactionId, request);
    }
}
