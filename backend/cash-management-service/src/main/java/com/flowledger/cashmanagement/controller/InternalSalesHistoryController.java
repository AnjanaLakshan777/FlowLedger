package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.dto.SalesHistoryResponse;
import com.flowledger.cashmanagement.service.SalesHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The recorded sales history for forecast-service's daily scheduled forecast, which runs without a
 * signed-in user. The same answer as the public sales-history API, for any company, so only
 * callers with the internal API key get here (SecurityConfig).
 */
@RestController
@RequestMapping("/internal/v1/cash-management/companies/{companyId}/sales-history")
@RequiredArgsConstructor
public class InternalSalesHistoryController {

    private final SalesHistoryService salesHistoryService;

    @GetMapping
    public SalesHistoryResponse history(
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return salesHistoryService.history(companyId, from, to);
    }
}
