package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.dto.BankBalanceSnapshotResponse;
import com.flowledger.bankintegration.service.BankBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-integration/companies/{companyId}/bank-accounts/{bankAccountId}/balance-snapshots")
@RequiredArgsConstructor
public class BankBalanceController {

    private final BankBalanceService bankBalanceService;

    @GetMapping("/latest")
    public BankBalanceSnapshotResponse getLatestBalance(@AuthenticationPrincipal Jwt user,
                                                        @PathVariable UUID companyId,
                                                        @PathVariable UUID bankAccountId) {
        return bankBalanceService.getLatestBalance(user, companyId, bankAccountId);
    }
}
