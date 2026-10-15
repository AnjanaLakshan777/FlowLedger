package com.flowledger.cashmanagement.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for identity-service's internal membership API over real HTTP, so the cash
 * service's client, headers, timeouts and error handling run exactly as they do against the
 * real service. Companies without a configured reply are answered with "not allowed".
 * identity-service's own rules (statuses, roles) are tested in identity-service.
 */
public final class FakeIdentityServer {

    private static final Pattern MEMBERSHIP_PATH =
            Pattern.compile("/internal/v1/identity/companies/([^/]+)/membership");
    private static final Runnable NOTHING = () -> { };
    private static final Reply DENIED =
            new Reply(200, "{\"allowed\":false,\"companyRole\":null,\"platformAdmin\":false}", Duration.ZERO, NOTHING);
    private static final FakeIdentityServer INSTANCE = new FakeIdentityServer();

    private final HttpServer server;
    private final Map<UUID, Reply> replies = new ConcurrentHashMap<>();
    private volatile String lastServiceKey;
    private volatile String lastAuthorization;

    private FakeIdentityServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-identity-server");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeIdentityServer instance() {
        return INSTANCE;
    }

    public String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public void reset() {
        replies.clear();
        lastServiceKey = null;
        lastAuthorization = null;
    }

    public void grant(UUID companyId, String companyRole) {
        reply(companyId, 200, allowedBody(companyRole));
    }

    // An active system admin without a membership in the company.
    public void grantPlatformAdmin(UUID companyId) {
        reply(companyId, 200, "{\"allowed\":true,\"companyRole\":null,\"platformAdmin\":true}");
    }

    public void reply(UUID companyId, int status, String body) {
        replies.put(companyId, new Reply(status, body, Duration.ZERO, NOTHING));
    }

    public void grantAfterDelay(UUID companyId, String companyRole, Duration delay) {
        replies.put(companyId, new Reply(200, allowedBody(companyRole), delay, NOTHING));
    }

    /**
     * Grants access, but first runs {@code whileCallerWaits} on the server thread. The calling
     * service is blocked on this request meanwhile, so the action observes it mid-check.
     */
    public void grantAfter(UUID companyId, String companyRole, Runnable whileCallerWaits) {
        replies.put(companyId, new Reply(200, allowedBody(companyRole), Duration.ZERO, whileCallerWaits));
    }

    public String lastServiceKey() {
        return lastServiceKey;
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastServiceKey = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");

        Matcher matcher = MEMBERSHIP_PATH.matcher(exchange.getRequestURI().getPath());
        Reply reply = matcher.matches()
                ? replies.getOrDefault(UUID.fromString(matcher.group(1)), DENIED)
                : new Reply(404, "{}", Duration.ZERO, NOTHING);

        try {
            reply.whileCallerWaits().run();
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

    private static String allowedBody(String companyRole) {
        return "{\"allowed\":true,\"companyRole\":\"" + companyRole + "\",\"platformAdmin\":false}";
    }

    private record Reply(int status, String body, Duration delay, Runnable whileCallerWaits) {
    }
}
