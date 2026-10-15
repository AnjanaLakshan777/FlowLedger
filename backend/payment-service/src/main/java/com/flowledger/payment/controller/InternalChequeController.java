package com.flowledger.payment.controller;

import com.flowledger.payment.dto.ChequeSummaryResponse;
import com.flowledger.payment.dto.InternalChequePage;
import com.flowledger.payment.repository.ChequeScanStore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Internal cheque API for other FlowLedger services (forecast-service's projection backfill and
 * verification). Protected by the internal API key, not by user tokens, and covers all companies.
 * See docs/decisions.md, section 9, for the contract and its consistency limits.
 */
@RestController
@RequestMapping("/internal/v1/payment/cheques")
@RequiredArgsConstructor
public class InternalChequeController {

    static final int MAX_PAGE_SIZE = 500;
    private static final String DEFAULT_PAGE_SIZE = "100";

    private final ChequeScanStore scanStore;

    @GetMapping
    public InternalChequePage scan(
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int limit) {
        // One extra row tells whether another page exists without a second query.
        List<InternalChequePage.Item> rows = scanStore.page(afterId, limit + 1);
        if (rows.size() <= limit) {
            return new InternalChequePage(rows, null);
        }
        List<InternalChequePage.Item> items = rows.subList(0, limit);
        return new InternalChequePage(List.copyOf(items), items.getLast().chequeId());
    }

    @GetMapping("/summary")
    public ChequeSummaryResponse summary(@RequestParam(required = false) UUID companyId) {
        List<ChequeSummaryResponse.Company> companies = scanStore.summary(companyId);
        long total = companies.stream().mapToLong(ChequeSummaryResponse.Company::chequeCount).sum();
        return new ChequeSummaryResponse(total, companies);
    }
}
