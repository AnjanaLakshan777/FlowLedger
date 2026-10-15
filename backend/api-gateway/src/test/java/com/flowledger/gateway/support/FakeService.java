package com.flowledger.gateway.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Stands in for one FlowLedger service over real HTTP and records the last request it received,
 * so tests see exactly what the gateway forwarded.
 */
public final class FakeService {

    public record Received(String method, String path, String query, Headers headers, byte[] body) {
    }

    private final HttpServer server;
    private volatile Received last;
    private volatile int status = 200;
    private volatile String body = "{\"ok\":true}";
    private volatile String contentType = "application/json";
    private volatile Duration delay = Duration.ZERO;

    public FakeService() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-service");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    public void reset() {
        last = null;
        status = 200;
        body = "{\"ok\":true}";
        contentType = "application/json";
        delay = Duration.ZERO;
    }

    public void reply(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    public Received last() {
        return last;
    }

    private void handle(HttpExchange exchange) throws IOException {
        last = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes());
        try {
            Thread.sleep(delay);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException | InterruptedException e) {
            // the gateway gave up first (timeout tests)
        } finally {
            exchange.close();
        }
    }
}
