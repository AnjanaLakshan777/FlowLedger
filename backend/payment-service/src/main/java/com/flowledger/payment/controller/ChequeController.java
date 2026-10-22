package com.flowledger.payment.controller;

import com.flowledger.payment.dto.BounceChequeRequest;
import com.flowledger.payment.dto.CancelChequeRequest;
import com.flowledger.payment.dto.ChequeRequest;
import com.flowledger.payment.dto.ChequeResponse;
import com.flowledger.payment.entity.ChequeStatus;
import com.flowledger.payment.service.ChequeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment/companies/{companyId}/cheques")
@RequiredArgsConstructor
public class ChequeController {

    private final ChequeService chequeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChequeResponse addCheque(@AuthenticationPrincipal Jwt user,
                                    @PathVariable UUID companyId,
                                    @Valid @RequestBody ChequeRequest request) {
        return chequeService.addCheque(user, companyId, request);
    }

    @GetMapping
    public List<ChequeResponse> listCheques(
            @AuthenticationPrincipal Jwt user,
            @PathVariable UUID companyId,
            @RequestParam(required = false) ChequeStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clearingFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clearingTo) {
        return chequeService.listCheques(user, companyId, status, clearingFrom, clearingTo);
    }

    @GetMapping("/{chequeId}")
    public ChequeResponse getCheque(@AuthenticationPrincipal Jwt user,
                                    @PathVariable UUID companyId,
                                    @PathVariable UUID chequeId) {
        return chequeService.getCheque(user, companyId, chequeId);
    }

    @PutMapping("/{chequeId}")
    public ChequeResponse updateCheque(@AuthenticationPrincipal Jwt user,
                                       @PathVariable UUID companyId,
                                       @PathVariable UUID chequeId,
                                       @Valid @RequestBody ChequeRequest request) {
        return chequeService.updateCheque(user, companyId, chequeId, request);
    }

    @PostMapping("/{chequeId}/cancel")
    public ChequeResponse cancelCheque(@AuthenticationPrincipal Jwt user,
                                       @PathVariable UUID companyId,
                                       @PathVariable UUID chequeId,
                                       @Valid @RequestBody CancelChequeRequest request) {
        return chequeService.cancelCheque(user, companyId, chequeId, request);
    }

    // The bank returned the cheque unpaid; it stays on record and leaves the forecast.
    @PostMapping("/{chequeId}/bounce")
    public ChequeResponse bounceCheque(@AuthenticationPrincipal Jwt user,
                                       @PathVariable UUID companyId,
                                       @PathVariable UUID chequeId,
                                       @Valid @RequestBody BounceChequeRequest request) {
        return chequeService.bounceCheque(user, companyId, chequeId, request);
    }
}
