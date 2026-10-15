package com.flowledger.forecast.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scan contract checks that keep a malformed page from moving the backfill cursor.
 */
class PaymentInternalClientTest {

    private static final UUID LOW = UUID.fromString("0fffffff-0000-0000-0000-000000000000");
    private static final UUID MID = UUID.fromString("7fffffff-0000-0000-0000-000000000000");
    private static final UUID HIGH = UUID.fromString("f0000000-0000-0000-0000-000000000000");

    @Test
    void shouldOrderUuidsLikeTheDatabase() {
        assertThat(PaymentInternalClient.compareInScanOrder(LOW, HIGH)).isNegative();
        assertThat(PaymentInternalClient.compareInScanOrder(HIGH, MID)).isPositive();
        assertThat(PaymentInternalClient.compareInScanOrder(MID, MID)).isZero();
        // Java's own order differs for IDs starting with 8 to f.
        assertThat(HIGH.compareTo(LOW)).isNegative();
    }

    @Test
    void shouldAcceptAValidPage() {
        PaymentInternalClient.ScanPage page = PaymentInternalClient.validated(page(HIGH, LOW, MID, HIGH), null, 3);

        assertThat(page.items()).hasSize(3);
        assertThat(page.nextAfterId()).isEqualTo(HIGH);
        assertThat(page.lastChequeId()).isEqualTo(HIGH);
    }

    @Test
    void shouldAcceptTheLastPage() {
        PaymentInternalClient.ScanPage page = PaymentInternalClient.validated(page(null, MID, HIGH), LOW, 5);

        assertThat(page.nextAfterId()).isNull();
        assertThat(PaymentInternalClient.validated(page(null), HIGH, 5).items()).isEmpty();
    }

    @Test
    void shouldRejectPagesThatBreakTheContract() {
        assertInvalid(page(null, HIGH, LOW), null, 5, "out of order");
        assertInvalid(page(null, MID, MID), null, 5, "out of order");
        assertInvalid(page(null, LOW), MID, 5, "out of order");
        assertInvalid(page(null, LOW, MID, HIGH), null, 2, "more items");
        assertInvalid(page(MID, LOW, HIGH), null, 2, "nextAfterId");
        assertInvalid(page(LOW), null, 2, "nextAfterId");
        assertInvalid(Map.of("nextAfterId", "x"), null, 2, "no items");
        assertInvalid(null, null, 2, "no items");

        Map<String, Object> noId = new HashMap<>(page(null, LOW));
        noId.put("items", List.of(Map.of("chequeId", "not-a-uuid")));
        assertInvalid(noId, null, 2, "chequeId");
        assertInvalid(Map.of("items", List.of("text")), null, 2, "not an object");
    }

    @Test
    void shouldRefuseToCallWithoutTheKey() {
        PaymentInternalClient client = new PaymentInternalClient(null,
                new com.flowledger.forecast.config.PaymentServiceProperties("http://localhost", null, null, " "));

        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.fetchPage(null, 10))
                .isInstanceOf(PaymentInternalClient.ScanFailedException.class)
                .hasMessage("PAYMENT_INTERNAL_API_SECRET is not set");
    }

    private static void assertInvalid(Map<String, Object> body, UUID afterId, int limit, String message) {
        assertThatThrownBy(() -> PaymentInternalClient.validated(body, afterId, limit))
                .isInstanceOf(PaymentInternalClient.ScanFailedException.class)
                .hasMessageContaining(message);
    }

    private static Map<String, Object> page(UUID nextAfterId, UUID... ids) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (UUID id : ids) {
            items.add(Map.of("chequeId", id.toString()));
        }
        Map<String, Object> page = new HashMap<>();
        page.put("items", items);
        page.put("nextAfterId", nextAfterId == null ? null : nextAfterId.toString());
        return page;
    }
}
