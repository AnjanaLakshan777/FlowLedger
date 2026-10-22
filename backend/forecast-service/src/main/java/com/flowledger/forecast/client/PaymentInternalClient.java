package com.flowledger.forecast.client;

import com.flowledger.forecast.config.PaymentServiceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads payment-service's internal cheque scan and summary with the service key (no user token).
 * Every page and summary is checked against its contract before it is used, so a malformed page
 * can never move the backfill cursor and a malformed summary never verifies anything. Items are
 * returned as parsed JSON for the caller's field validation.
 */
@Component
public class PaymentInternalClient {

    static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String SCAN_PATH = "/internal/v1/payment/cheques";
    private static final String SUMMARY_PATH = "/internal/v1/payment/cheques/summary";

    private final RestClient restClient;
    private final String internalApiSecret;

    public PaymentInternalClient(@Qualifier("paymentRestClient") RestClient restClient,
                                 PaymentServiceProperties properties) {
        this.restClient = restClient;
        this.internalApiSecret = properties.internalApiSecret();
    }

    /** Thrown when a page cannot be read or breaks the contract. The message is safe to log and store. */
    public static class ScanFailedException extends RuntimeException {
        public ScanFailedException(String message) {
            super(message);
        }
    }

    /**
     * @param items       the page's items in scan order, each with a valid chequeId
     * @param nextAfterId the cursor for the next page, or null at the end of the scan
     */
    public record ScanPage(List<Map<String, Object>> items, UUID nextAfterId) {

        public UUID lastChequeId() {
            return chequeId(items.getLast());
        }
    }

    public boolean configured() {
        return internalApiSecret != null && !internalApiSecret.isBlank();
    }

    /**
     * One company's figures in payment-service's cheque summary.
     */
    public record CompanyChequeSummary(UUID companyId, long chequeCount, long issuedCount, long cancelledCount,
                                       long versionSum) {
    }

    public ScanPage fetchPage(UUID afterId, int limit) {
        Map<String, Object> body = get(uri -> {
            uri.path(SCAN_PATH).queryParam("limit", limit);
            if (afterId != null) {
                uri.queryParam("afterId", afterId);
            }
            return uri.build();
        });
        return validated(body, afterId, limit);
    }

    /**
     * payment-service's cheque summary, for every company with cheques or only the given one. All
     * figures come from one statement there. A company without cheques has no entry.
     */
    public Map<UUID, CompanyChequeSummary> fetchSummary(UUID companyId) {
        Map<String, Object> body = get(uri -> {
            uri.path(SUMMARY_PATH);
            if (companyId != null) {
                uri.queryParam("companyId", companyId);
            }
            return uri.build();
        });
        return validatedSummary(body, companyId);
    }

    private Map<String, Object> get(Function<UriBuilder, URI> uri) {
        if (!configured()) {
            throw new ScanFailedException("PAYMENT_INTERNAL_API_SECRET is not set");
        }
        try {
            return restClient.get()
                    .uri(uri)
                    .header(KEY_HEADER, internalApiSecret)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException e) {
            throw new ScanFailedException("payment-service answered " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new ScanFailedException("payment-service unavailable (" + e.getClass().getSimpleName() + ")");
        }
    }

    // The summary contract: whole non-negative numbers, ISSUED + CANCELLED + BOUNCED = cheques, every version
    // at least 1, one entry per company, totals adding up, and only the requested company if any.
    @SuppressWarnings("unchecked")
    static Map<UUID, CompanyChequeSummary> validatedSummary(Map<String, Object> body, UUID companyId) {
        if (body == null || !(body.get("companies") instanceof List<?> companies)) {
            throw new ScanFailedException("Invalid cheque summary: no companies");
        }
        long total = count(body.get("chequeCount"));
        Map<UUID, CompanyChequeSummary> summaries = new LinkedHashMap<>();
        long sum = 0;
        for (Object entry : companies) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new ScanFailedException("Invalid cheque summary: entry is not an object");
            }
            Map<String, Object> company = (Map<String, Object>) raw;
            UUID id = company.get("companyId") instanceof String text ? parse(text) : null;
            if (id == null || (companyId != null && !companyId.equals(id))) {
                throw new ScanFailedException("Invalid cheque summary: unexpected companyId");
            }
            CompanyChequeSummary summary = new CompanyChequeSummary(id, count(company.get("chequeCount")),
                    count(company.get("issuedCount")), count(company.get("cancelledCount")),
                    count(company.get("versionSum")));
            // Absent from payment-service versions without bounced cheques, where it is always 0.
            long bounced = company.containsKey("bouncedCount") ? count(company.get("bouncedCount")) : 0;
            if (summary.issuedCount() + summary.cancelledCount() + bounced != summary.chequeCount()
                    || summary.versionSum() < summary.chequeCount() || summary.chequeCount() == 0) {
                throw new ScanFailedException("Invalid cheque summary: inconsistent company figures");
            }
            if (summaries.put(id, summary) != null) {
                throw new ScanFailedException("Invalid cheque summary: duplicate companyId");
            }
            sum += summary.chequeCount();
        }
        if (sum != total) {
            throw new ScanFailedException("Invalid cheque summary: totals do not add up");
        }
        return summaries;
    }

    private static long count(Object value) {
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0) {
            return ((Number) value).longValue();
        }
        throw new ScanFailedException("Invalid cheque summary: count is not a whole non-negative number");
    }

    // The scan contract: items ascend strictly by ID in the database's order, all after afterId,
    // at most limit; nextAfterId is null or the last item's ID.
    @SuppressWarnings("unchecked")
    static ScanPage validated(Map<String, Object> body, UUID afterId, int limit) {
        if (body == null || !(body.get("items") instanceof List<?> rawItems)) {
            throw new ScanFailedException("Invalid scan page: no items");
        }
        if (rawItems.size() > limit) {
            throw new ScanFailedException("Invalid scan page: more items than requested");
        }
        UUID previous = afterId;
        for (Object item : rawItems) {
            if (!(item instanceof Map<?, ?>)) {
                throw new ScanFailedException("Invalid scan page: item is not an object");
            }
            UUID chequeId = chequeId((Map<String, Object>) item);
            if (previous != null && compareInScanOrder(chequeId, previous) <= 0) {
                throw new ScanFailedException("Invalid scan page: items out of order");
            }
            previous = chequeId;
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) rawItems;

        UUID nextAfterId = null;
        Object next = body.get("nextAfterId");
        if (next != null) {
            nextAfterId = next instanceof String text ? parse(text) : null;
            if (nextAfterId == null || items.isEmpty() || !nextAfterId.equals(chequeId(items.getLast()))) {
                throw new ScanFailedException("Invalid scan page: nextAfterId does not match the last item");
            }
        }
        return new ScanPage(List.copyOf(items), nextAfterId);
    }

    /**
     * PostgreSQL's UUID order: unsigned bytes, the order of the lowercase text. Not
     * {@link UUID#compareTo}, which compares signed halves.
     */
    public static int compareInScanOrder(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static UUID chequeId(Map<String, Object> item) {
        UUID chequeId = item.get("chequeId") instanceof String text ? parse(text) : null;
        if (chequeId == null) {
            throw new ScanFailedException("Invalid scan page: item without a valid chequeId");
        }
        return chequeId;
    }

    private static UUID parse(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
