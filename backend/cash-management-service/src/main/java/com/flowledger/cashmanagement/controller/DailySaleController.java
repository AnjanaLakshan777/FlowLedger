package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.dto.CreateDailySaleRequest;
import com.flowledger.cashmanagement.dto.DailySaleResponse;
import com.flowledger.cashmanagement.service.DailySaleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cash-management/companies/{companyId}/sales")
@RequiredArgsConstructor
public class DailySaleController {

    private final DailySaleService dailySaleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DailySaleResponse createDailySale(@AuthenticationPrincipal Jwt user,
                                             @PathVariable UUID companyId,
                                             @Valid @RequestBody CreateDailySaleRequest request) {
        return dailySaleService.createDailySale(user, companyId, request);
    }

    @GetMapping
    public List<DailySaleResponse> listDailySales(
            @AuthenticationPrincipal Jwt user,
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dailySaleService.listDailySales(user, companyId, from, to);
    }

    @GetMapping("/{saleId}")
    public DailySaleResponse getDailySale(@AuthenticationPrincipal Jwt user,
                                          @PathVariable UUID companyId,
                                          @PathVariable UUID saleId) {
        return dailySaleService.getDailySale(user, companyId, saleId);
    }
}
