package com.flowledger.payment.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for bank-integration-service's bank account list over real HTTP, so the payment
 * service's client, forwarded token and error handling run as they do against the real service.
 * Companies without a configured reply have no bank accounts.
 */
public final class FakeBankIntegrationServer {

    private static final Pattern ACCOUNTS_PATH =
            Pattern.compile("/api/v1/bank-integration/companies/([^/]+)/bank-accounts");
    private static final Runnable NOTHING = () -> { };
    private static final FakeBankIntegrationServer INSTANCE = new FakeBankIntegrationServer();

    private final HttpServer server;
    private final Map<UUID, Reply> replies = new ConcurrentHashMap<>();
    private volatile String lastAuthorization;

    private FakeBankIntegrationServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-bank-integration-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeBankIntegrationServer instance() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public void reset() {
        replies.clear();
        lastAuthorization = null;
    }

    // The company's active current account, next to an inactive one and the other fields the
    // real service returns.
    public void currentAccount(UUID companyId, UUID accountId) {
        currentAccountWhile(companyId, accountId, NOTHING);
    }

    // As currentAccount, but first runs whileCallerWaits while the payment service waits for the answer.
    public void currentAccountWhile(UUID companyId, UUID accountId, Runnable whileCallerWaits) {
        String body = """
                [{"id":"%s","companyId":"%s","bankName":"Old Bank","accountName":"Old","accountType":"CURRENT",
                  "accountNumberLast4":"1111","currency":"LKR","integrationMode":"MANUAL","status":"INACTIVE"},
                 {"id":"%s","companyId":"%s","bankName":"Commercial Bank of Ceylon","accountName":"Current",
                  "accountType":"CURRENT","accountNumberLast4":"4821","currency":"LKR",
                  "integrationMode":"STATEMENT_IMPORT","status":"ACTIVE"}]
                """.formatted(UUID.randomUUID(), companyId, accountId, companyId);
        replies.put(companyId, new Reply(200, body, whileCallerWaits));
    }

    public void reply(UUID companyId, int status, String body) {
        replies.put(companyId, new Reply(status, body, NOTHING));
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");

        Matcher matcher = ACCOUNTS_PATH.matcher(exchange.getRequestURI().getPath());
        Reply reply = matcher.matches()
                ? replies.getOrDefault(UUID.fromString(matcher.group(1)), new Reply(200, "[]", NOTHING))
                : new Reply(404, "{}", NOTHING);

        try {
            reply.whileCallerWaits().run();
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            // The client gave up first; nothing left to answer.
        } finally {
            exchange.close();
        }
    }

    private record Reply(int status, String body, Runnable whileCallerWaits) {
    }
}
