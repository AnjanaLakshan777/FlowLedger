package com.flowledger.forecast.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Stands in for the APIs of bank-integration-service and payment-service over real HTTP. Replies
 * are configured per request path; any other path is answered with 404. Both services' paths
 * start differently, so one server serves both. Their own rules are tested in their services.
 */
public final class FakeSourceServices {

    private static final FakeSourceServices INSTANCE = new FakeSourceServices();

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile String lastAuthorization;
    private volatile Runnable onRequest = () -> { };

    private record Reply(int status, String body, Duration delay) {
    }

    private FakeSourceServices() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-source-services");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeSourceServices instance() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public void reset() {
        replies.clear();
        requests.clear();
        lastAuthorization = null;
        onRequest = () -> { };
    }

    // Runs on the server thread for every request, while the calling service waits for the answer.
    public void onRequest(Runnable action) {
        onRequest = action;
    }

    public void reply(String path, int status, String body) {
        replies.put(path, new Reply(status, body, Duration.ZERO));
    }

    public void replyAfterDelay(String path, String body, Duration delay) {
        replies.put(path, new Reply(200, body, delay));
    }

    // Requests received, as "path?query".
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    // --- bank-integration-service

    public static String accountsPath(UUID companyId) {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-accounts";
    }

    public static String balancePath(UUID companyId, UUID accountId) {
        return accountsPath(companyId) + "/" + accountId + "/balance-snapshots/latest";
    }

    public static String importsPath(UUID companyId) {
        return "/api/v1/bank-integration/companies/" + companyId + "/statement-imports";
    }

    public static String transactionsPath(UUID companyId) {
        return "/api/v1/bank-integration/companies/" + companyId + "/bank-transactions";
    }

    public static String chequesPath(UUID companyId) {
        return "/api/v1/payment/companies/" + companyId + "/cheques";
    }

    public void currentAccount(UUID companyId, UUID accountId) {
        reply(accountsPath(companyId), 200, """
                [{"id": "%s", "companyId": "%s", "accountType": "CURRENT", "status": "ACTIVE", "currency": "LKR"}]
                """.formatted(accountId, companyId));
    }

    public void balance(UUID companyId, UUID accountId, String amount, String asOf) {
        reply(balancePath(companyId, accountId), 200, """
                {"id": "%s", "companyId": "%s", "bankAccountId": "%s", "balanceAmount": %s, "currency": "LKR",
                 "balanceAsOf": "%s", "sourceType": "STATEMENT_IMPORT"}
                """.formatted(UUID.randomUUID(), companyId, accountId, amount, asOf));
    }

    public void statements(UUID companyId, UUID accountId, LocalDate... periodBounds) {
        StringBuilder batches = new StringBuilder();
        for (int i = 0; i < periodBounds.length; i += 2) {
            if (!batches.isEmpty()) {
                batches.append(',');
            }
            batches.append("""
                    {"id": "%s", "companyId": "%s", "bankAccountId": "%s", "status": "IMPORTED",
                     "periodStart": "%s", "periodEnd": "%s"}
                    """.formatted(UUID.randomUUID(), companyId, accountId, periodBounds[i], periodBounds[i + 1]));
        }
        reply(importsPath(companyId), 200, "[" + batches + "]");
    }

    public void transactions(UUID companyId, List<String> transactions) {
        reply(transactionsPath(companyId), 200, "[" + String.join(",", transactions) + "]");
    }

    public void cheques(UUID companyId, List<String> cheques) {
        reply(chequesPath(companyId), 200, "[" + String.join(",", cheques) + "]");
    }

    public static String transaction(UUID accountId, LocalDate date, String direction, String amount,
                                     String category) {
        return """
                {"id": "%s", "bankAccountId": "%s", "transactionDate": "%s", "direction": "%s", "amount": %s,
                 "currency": "LKR", "runningBalance": null, "category": "%s", "description": "not used"}
                """.formatted(UUID.randomUUID(), accountId, date, direction, amount, category);
    }

    public static String cheque(UUID chequeId, UUID accountId, String number, String amount, LocalDate clearing,
                                String status) {
        return """
                {"id": "%s", "bankAccountId": "%s", "chequeNumber": "%s", "payeeName": "Lanka Tiles",
                 "amount": %s, "expectedClearingDate": "%s", "status": "%s", "version": 1}
                """.formatted(chequeId, accountId, number, amount, clearing, status);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(query == null ? path : path + "?" + query);
        Reply reply = replies.getOrDefault(path, new Reply(404, "{}", Duration.ZERO));

        try {
            onRequest.run();
            Thread.sleep(reply.delay());
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException | InterruptedException e) {
            // The client gave up first (timeout tests); nothing left to answer.
        } finally {
            exchange.close();
        }
    }

    public static String queryOf(String request) {
        return request.contains("?") ? request.substring(request.indexOf('?') + 1) : "";
    }

    public static Map<String, String> params(String request) {
        String query = queryOf(request);
        if (query.isEmpty()) {
            return Map.of();
        }
        return List.of(query.split("&")).stream()
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair.length > 1 ? pair[1] : ""));
    }
}
