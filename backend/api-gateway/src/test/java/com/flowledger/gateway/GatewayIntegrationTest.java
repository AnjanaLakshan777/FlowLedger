package com.flowledger.gateway;

import com.flowledger.gateway.support.FakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway in front of fake services over real HTTP: routing, forwarded headers and bodies,
 * pass-through of the services' own authorization answers, CORS, and failed services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    private static final FakeService identity = new FakeService();
    private static final FakeService cash = new FakeService();
    private static final FakeService bank = new FakeService();
    private static final FakeService payment = new FakeService();
    private static final FakeService forecast = new FakeService();
    private static final Map<String, FakeService> SERVICES = Map.of(
            "identity", identity, "cash-management", cash, "bank-integration", bank, "payment", payment, "forecast", forecast);
    private static final String TOKEN = "Bearer user-access-token";

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", identity::url);
        registry.add("CASH_MANAGEMENT_SERVICE_URL", cash::url);
        registry.add("BANK_INTEGRATION_SERVICE_URL", bank::url);
        registry.add("PAYMENT_SERVICE_URL", payment::url);
        registry.add("FORECAST_SERVICE_URL", forecast::url);
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() {
        SERVICES.values().forEach(FakeService::reset);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "identity, /api/v1/identity/me",
            "cash-management, /api/v1/cash-management/companies/c1/sales",
            "bank-integration, /api/v1/bank-integration/companies/c1/bank-transactions",
            "payment, /api/v1/payment/companies/c1/cheques",
            "forecast, /api/v1/forecast/companies/c1/forecast-runs",
    })
    void shouldRouteEachPrefixToItsServiceWithThePathUnchanged(String service, String path) throws Exception {
        HttpResponse<String> response = send(get(path + "?from=2026-10-01&to=2026-10-31").header("Authorization", TOKEN));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"ok\":true}");
        FakeService target = SERVICES.get(service);
        assertThat(target.last().path()).isEqualTo(path);
        assertThat(target.last().query()).isEqualTo("from=2026-10-01&to=2026-10-31");
        SERVICES.values().stream().filter(other -> other != target).forEach(other -> assertThat(other.last()).isNull());
    }

    @Test
    void shouldForwardLoginWithoutATokenAndKeepTheJsonBody() throws Exception {
        String json = "{\"email\":\"fc@flowledger.test\",\"password\":\"E2e-Test-Password-2026\"}";

        HttpResponse<String> response = send(request("/api/v1/identity/auth/login")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(identity.last().method()).isEqualTo("POST");
        assertThat(new String(identity.last().body(), StandardCharsets.UTF_8)).isEqualTo(json);
        assertThat(MediaType.parseMediaType(identity.last().headers().getFirst("Content-Type")).isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(identity.last().headers().getFirst("Authorization")).isNull();
    }

    @Test
    void shouldForwardTheUserTokenButNeverAnInternalApiKey() throws Exception {
        send(get("/api/v1/payment/companies/c1/cheques").header("Authorization", TOKEN).header("X-Internal-Api-Key", "guessed-key"));

        assertThat(payment.last().headers().getFirst("Authorization")).isEqualTo(TOKEN);
        assertThat(payment.last().headers().containsKey("X-internal-api-key")).isFalse();
    }

    // The gateway adds no access of its own: the services' 401 and 403 reach the client unchanged.
    @ParameterizedTest
    @CsvSource({"401, Full authentication is required", "403, You do not have access to this company."})
    void shouldPassTheServicesAuthorizationAnswersThrough(int status, String detail) throws Exception {
        String problem = "{\"status\":" + status + ",\"detail\":\"" + detail + "\"}";
        forecast.reply(status, "application/problem+json", problem);

        HttpResponse<String> response = send(request("/api/v1/forecast/companies/c1/forecast-runs").POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(problem);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void shouldStreamMultipartUploadsUnchanged() throws Exception {
        String boundary = "flowledger-" + UUID.randomUUID();
        byte[] csv = "Date,Description\r\n9/30/2026,\"INTEREST WITHHELD\"\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] body = concat(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"september.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8), csv, ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = send(request("/api/v1/bank-integration/companies/c1/bank-accounts/a1/statement-imports")
                .header("Authorization", TOKEN)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));

        assertThat(response.statusCode()).isEqualTo(200);
        // Spring may add a charset parameter; the media type and boundary must be unchanged
        MediaType forwarded = MediaType.parseMediaType(bank.last().headers().getFirst("Content-Type"));
        assertThat(forwarded.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
        assertThat(forwarded.getParameter("boundary")).isEqualTo(boundary);
        assertThat(bank.last().body()).isEqualTo(body);
    }

    // The statement import limit (2 MB) belongs to bank-integration-service, not to the gateway.
    @Test
    void shouldNotTruncateLargeUploads() throws Exception {
        byte[] body = new byte[3 * 1024 * 1024];
        Arrays.fill(body, (byte) 'x');

        send(request("/api/v1/bank-integration/companies/c1/bank-accounts/a1/statement-imports")
                .header("Content-Type", "multipart/form-data; boundary=x")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));

        assertThat(bank.last().body()).hasSize(body.length);
    }

    @ParameterizedTest
    @CsvSource({"/internal/v1/identity/companies/c1/membership", "/internal/v1/payment/cheques",
            "/internal/v1/payment/cheques/summary", "/api/v1/unknown/x", "/api/v2/identity/me"})
    void shouldNotRouteInternalOrUnknownPaths(String path) throws Exception {
        HttpResponse<String> response = send(get(path).header("Authorization", TOKEN));

        assertThat(response.statusCode()).isEqualTo(404);
        SERVICES.values().forEach(service -> assertThat(service.last()).isNull());
    }

    @Test
    void shouldAnswer504WhenAServiceDoesNotAnswerInTime() throws Exception {
        // the test read timeout is 1s
        cash.delay(Duration.ofSeconds(3));

        HttpResponse<String> response = send(get("/api/v1/cash-management/companies/c1/sales"));

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("did not answer in time");
    }

    @Test
    void shouldAllowTheFrontendOriginAndRefuseOthers() throws Exception {
        HttpResponse<String> allowed = send(preflight("http://localhost:5173"));
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("authorization");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

        HttpResponse<String> refused = send(preflight("https://evil.example"));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        SERVICES.values().forEach(service -> assertThat(service.last()).isNull());
    }

    @Test
    void shouldReportItsOwnHealth() throws Exception {
        HttpResponse<String> response = send(get("/api/v1/gateway/health"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"service\":\"api-gateway\"");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private HttpRequest.Builder get(String path) {
        return request(path).GET();
    }

    private HttpRequest.Builder preflight(String origin) {
        return request("/api/v1/forecast/companies/c1/forecast-runs")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }
}
