package com.flowledger.forecast.client;

import com.flowledger.forecast.config.PaymentServiceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads payment-service's internal cheque scan with the service key (no user token). Every page
 * is checked against the scan contract before it is used, so a malformed page can never move the
 * backfill cursor. Items are returned as parsed JSON for the caller's field validation.
 */
@Component
public class PaymentInternalClient {

    static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String SCAN_PATH = "/internal/v1/payment/cheques";

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

    public ScanPage fetchPage(UUID afterId, int limit) {
        if (!configured()) {
            throw new ScanFailedException("PAYMENT_INTERNAL_API_SECRET is not set");
        }
        Map<String, Object> body;
        try {
            body = restClient.get()
                    .uri(uri -> {
                        uri.path(SCAN_PATH).queryParam("limit", limit);
                        if (afterId != null) {
                            uri.queryParam("afterId", afterId);
                        }
                        return uri.build();
                    })
                    .header(KEY_HEADER, internalApiSecret)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientResponseException e) {
            throw new ScanFailedException("payment-service answered " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new ScanFailedException("payment-service unavailable (" + e.getClass().getSimpleName() + ")");
        }
        return validated(body, afterId, limit);
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
