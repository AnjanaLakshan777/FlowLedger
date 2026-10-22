package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.dto.ApproveSalesImportRequest;
import com.flowledger.cashmanagement.dto.RejectSalesImportRequest;
import com.flowledger.cashmanagement.dto.ReverseSalesImportRequest;
import com.flowledger.cashmanagement.dto.SalesImportDetailResponse;
import com.flowledger.cashmanagement.dto.SalesImportOverviewResponse;
import com.flowledger.cashmanagement.service.SalesImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cash-management/companies/{companyId}/sales-imports")
@RequiredArgsConstructor
public class SalesImportController {

    private final SalesImportService salesImportService;

    @GetMapping
    public SalesImportOverviewResponse list(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return salesImportService.list(user, companyId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SalesImportDetailResponse upload(@AuthenticationPrincipal Jwt user,
                                            @PathVariable UUID companyId,
                                            @RequestParam(value = "file", required = false) MultipartFile file) {
        return salesImportService.upload(user, companyId, file);
    }

    @GetMapping("/{importId}")
    public SalesImportDetailResponse get(@AuthenticationPrincipal Jwt user,
                                         @PathVariable UUID companyId,
                                         @PathVariable UUID importId) {
        return salesImportService.get(user, companyId, importId);
    }

    @PostMapping("/{importId}/approve")
    public SalesImportDetailResponse approve(@AuthenticationPrincipal Jwt user,
                                             @PathVariable UUID companyId,
                                             @PathVariable UUID importId,
                                             @Valid @RequestBody ApproveSalesImportRequest request) {
        return salesImportService.approve(user, companyId, importId, request);
    }

    @PostMapping("/{importId}/reject")
    public SalesImportDetailResponse reject(@AuthenticationPrincipal Jwt user,
                                            @PathVariable UUID companyId,
                                            @PathVariable UUID importId,
                                            @Valid @RequestBody RejectSalesImportRequest request) {
        return salesImportService.reject(user, companyId, importId, request);
    }

    // Deletes the daily sales an approved import recorded; the import stays as REVERSED history.
    @PostMapping("/{importId}/reverse")
    public SalesImportDetailResponse reverse(@AuthenticationPrincipal Jwt user,
                                             @PathVariable UUID companyId,
                                             @PathVariable UUID importId,
                                             @Valid @RequestBody ReverseSalesImportRequest request) {
        return salesImportService.reverse(user, companyId, importId, request);
    }
}
