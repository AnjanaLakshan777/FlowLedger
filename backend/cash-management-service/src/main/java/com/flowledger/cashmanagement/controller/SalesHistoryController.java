package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.dto.SalesHistoryResponse;
import com.flowledger.cashmanagement.service.SalesHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cash-management/companies/{companyId}/sales-history")
@RequiredArgsConstructor
public class SalesHistoryController {

    private final SalesHistoryService salesHistoryService;

    @GetMapping
    public SalesHistoryResponse approvedHistory(
            @AuthenticationPrincipal Jwt user,
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return salesHistoryService.approvedHistory(user, companyId, from, to);
    }
}
