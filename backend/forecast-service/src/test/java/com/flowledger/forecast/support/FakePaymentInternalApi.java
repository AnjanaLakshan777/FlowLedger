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
 * Stands in for payment-service's internal cheque scan and summary over real HTTP: keyset pages
 * ordered like PostgreSQL orders UUIDs (their lowercase text), per-company summary figures from the
 * same cheques, the internal API key check, and injected failures. The rules themselves are tested
 * in payment-service.
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
    private final List<String> requestedSummaries = new CopyOnWriteArrayList<>();
    private volatile int summaryFailures;
    private volatile UnaryOperator<Map<String, Object>> summaryRewrite = UnaryOperator.identity();
    private volatile Runnable beforeSummary = () -> { };

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
        requestedSummaries.clear();
        summaryFailures = 0;
        summaryRewrite = UnaryOperator.identity();
        beforeSummary = () -> { };
    }

    public void removeCheque(UUID chequeId) {
        cheques.remove(chequeId.toString());
    }

    /** Answers the next {@code times} summary requests with the failure status. */
    public void failSummary(int times) {
        summaryFailures = times;
    }

    /** Changes every summary body before it is sent, to break the contract. */
    public void rewriteSummary(UnaryOperator<Map<String, Object>> rewrite) {
        summaryRewrite = rewrite;
    }

    /** Runs when a summary is requested, before its figures are computed. */
    public void beforeSummary(Runnable action) {
        beforeSummary = action;
    }

    /** The companyId of every summary request in order ("null" for all companies). */
    public List<String> requestedSummaries() {
        return List.copyOf(requestedSummaries);
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
            if (exchange.getRequestURI().getPath().endsWith("/summary")) {
                summary(exchange, query.get("companyId"));
                return;
            }
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

    private void summary(HttpExchange exchange, String companyId) throws IOException {
        requestedSummaries.add(String.valueOf(companyId));
        if (takeSummaryFailure()) {
            send(exchange, failureStatus, Map.of("detail", "injected failure"));
            return;
        }
        beforeSummary.run();
        Map<String, long[]> figures = new java.util.TreeMap<>();
        for (Map<String, Object> item : cheques.values()) {
            String owner = (String) item.get("companyId");
            if (companyId != null && !companyId.equals(owner)) {
                continue;
            }
            long[] f = figures.computeIfAbsent(owner, key -> new long[5]);
            f[0]++;
            f[switch (String.valueOf(item.get("status"))) { case "ISSUED" -> 1; case "BOUNCED" -> 4; default -> 2; }]++;
            f[3] += ((Number) item.get("version")).longValue();
        }
        List<Map<String, Object>> companies = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, long[]> entry : figures.entrySet()) {
            long[] f = entry.getValue();
            Map<String, Object> company = new LinkedHashMap<>();
            company.put("companyId", entry.getKey());
            company.put("chequeCount", f[0]);
            company.put("issuedCount", f[1]);
            company.put("cancelledCount", f[2]);
            company.put("bouncedCount", f[4]);
            company.put("versionSum", f[3]);
            companies.add(company);
            total += f[0];
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chequeCount", total);
        body.put("companies", companies);
        send(exchange, 200, summaryRewrite.apply(body));
    }

    private synchronized boolean takeSummaryFailure() {
        if (summaryFailures == 0) {
            return false;
        }
        summaryFailures--;
        return true;
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
