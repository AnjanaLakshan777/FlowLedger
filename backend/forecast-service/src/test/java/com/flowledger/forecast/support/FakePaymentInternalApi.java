package com.flowledger.forecast.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Stands in for payment-service's internal cheque scan over real HTTP: keyset pages ordered like
 * PostgreSQL orders UUIDs (their lowercase text), the internal API key check, and injected
 * failures. The scan rules themselves are tested in payment-service.
 */
public final class FakePaymentInternalApi {

    private static final FakePaymentInternalApi INSTANCE = new FakePaymentInternalApi();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    // Keyed by the cheque ID's text, which sorts like the database.
    private final ConcurrentSkipListMap<String, Map<String, Object>> cheques = new ConcurrentSkipListMap<>();
    private final List<String> requestedAfterIds = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> failuresByAfterId = new HashMap<>();
    private volatile String expectedKey;
    private volatile int failureStatus = 503;
    private volatile UnaryOperator<Map<String, Object>> pageRewrite = UnaryOperator.identity();
    private volatile Consumer<String> beforeResponse = afterId -> { };

    private FakePaymentInternalApi() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-payment-internal-api");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/internal/v1/payment/cheques", this::handle);
        server.start();
    }

    public static FakePaymentInternalApi instance() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public synchronized void reset(String key) {
        cheques.clear();
        requestedAfterIds.clear();
        failuresByAfterId.clear();
        expectedKey = key;
        failureStatus = 503;
        pageRewrite = UnaryOperator.identity();
        beforeResponse = afterId -> { };
    }

    /** Adds or replaces a cheque as the scan returns it; returns its item for later changes. */
    public Map<String, Object> cheque(UUID chequeId, UUID companyId, String status, int version) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chequeId", chequeId.toString());
        item.put("companyId", companyId.toString());
        item.put("bankAccountId", "5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f");
        item.put("chequeNumber", "000123");
        item.put("category", "SUPPLIER_PAYMENT");
        item.put("amount", "1500.00");
        item.put("issueDate", "2026-10-01");
        item.put("expectedClearingDate", "2026-10-22");
        item.put("status", status);
        item.put("version", version);
        cheques.put(chequeId.toString(), item);
        return item;
    }

    /** Answers the next {@code times} requests for this cursor (null: the first page) with an error. */
    public synchronized void failPage(UUID afterId, int times) {
        failuresByAfterId.put(String.valueOf(afterId), times);
    }

    public void failureStatus(int status) {
        failureStatus = status;
    }

    /** Changes every page body before it is sent, to break the scan contract. */
    public void rewritePages(UnaryOperator<Map<String, Object>> rewrite) {
        pageRewrite = rewrite;
    }

    /** Runs after a page is read and before it is sent, with the page's afterId ("null" for the first). */
    public void beforeResponse(Consumer<String> action) {
        beforeResponse = action;
    }

    /** The afterId of every page request in order ("null" for the first page). */
    public List<String> requestedAfterIds() {
        return List.copyOf(requestedAfterIds);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!String.valueOf(expectedKey).equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"))) {
                send(exchange, 401, Map.of("detail", "Invalid service credentials."));
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String afterId = query.getOrDefault("afterId", "null");
            int limit = Integer.parseInt(query.getOrDefault("limit", "100"));
            requestedAfterIds.add(afterId);
            if (takeFailure(afterId)) {
                send(exchange, failureStatus, Map.of("detail", "injected failure"));
                return;
            }

            List<Map<String, Object>> items = new ArrayList<>();
            var tail = "null".equals(afterId) ? cheques : cheques.tailMap(afterId, false);
            for (Map<String, Object> item : tail.values()) {
                if (items.size() == limit + 1) {
                    break;
                }
                items.add(new LinkedHashMap<>(item));
            }
            Map<String, Object> page = new LinkedHashMap<>();
            if (items.size() > limit) {
                items = items.subList(0, limit);
                page.put("items", items);
                page.put("nextAfterId", items.getLast().get("chequeId"));
            } else {
                page.put("items", items);
                page.put("nextAfterId", null);
            }
            beforeResponse.accept(afterId);
            send(exchange, 200, pageRewrite.apply(page));
        }
    }

    private synchronized boolean takeFailure(String afterId) {
        Integer remaining = failuresByAfterId.get(afterId);
        if (remaining == null || remaining == 0) {
            return false;
        }
        failuresByAfterId.put(afterId, remaining - 1);
        return true;
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(parts[0], parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return values;
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
