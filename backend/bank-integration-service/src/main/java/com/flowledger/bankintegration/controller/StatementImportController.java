package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.dto.StatementImportResponse;
import com.flowledger.bankintegration.service.StatementImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-integration/companies/{companyId}")
@RequiredArgsConstructor
public class StatementImportController {

    private final StatementImportService statementImportService;

    @PostMapping(path = "/bank-accounts/{bankAccountId}/statement-imports",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StatementImportResponse importStatement(@AuthenticationPrincipal Jwt user,
                                                   @PathVariable UUID companyId,
                                                   @PathVariable UUID bankAccountId,
                                                   @RequestParam("file") MultipartFile file) {
        return statementImportService.importStatement(user, companyId, bankAccountId, file);
    }

    // One ended month of transactions from FlowLedger's monthly template, each with its category.
    @PostMapping(path = "/bank-accounts/{bankAccountId}/monthly-imports",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StatementImportResponse importMonth(@AuthenticationPrincipal Jwt user,
                                               @PathVariable UUID companyId,
                                               @PathVariable UUID bankAccountId,
                                               @RequestParam(value = "month", required = false) String month,
                                               @RequestParam("file") MultipartFile file) {
        return statementImportService.importMonth(user, companyId, bankAccountId, month, file);
    }

    @GetMapping("/statement-imports")
    public List<StatementImportResponse> listImports(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return statementImportService.listImports(user, companyId);
    }

    @GetMapping("/statement-imports/{batchId}")
    public StatementImportResponse getImport(@AuthenticationPrincipal Jwt user,
                                             @PathVariable UUID companyId,
                                             @PathVariable UUID batchId) {
        return statementImportService.getImport(user, companyId, batchId);
    }

    // Removes a statement uploaded by mistake: its transactions and balance are deleted, and the
    // import stays in the history as REMOVED.
    @DeleteMapping("/statement-imports/{batchId}")
    public StatementImportResponse removeImport(@AuthenticationPrincipal Jwt user,
                                                @PathVariable UUID companyId,
                                                @PathVariable UUID batchId) {
        return statementImportService.removeImport(user, companyId, batchId);
    }
}
