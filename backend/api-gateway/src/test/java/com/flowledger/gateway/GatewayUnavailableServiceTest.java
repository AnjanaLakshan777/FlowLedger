package com.flowledger.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** A route whose service is not running: the client gets a clear 503, not a stack trace or a hang. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayUnavailableServiceTest {

    @DynamicPropertySource
    static void deadService(DynamicPropertyRegistry registry) {
        registry.add("CASH_MANAGEMENT_SERVICE_URL", GatewayUnavailableServiceTest::unusedPortUrl);
    }

    @LocalServerPort
    private int port;

    @Test
    void shouldAnswer503WhenAServiceCannotBeReached() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/cash-management/companies/c1/sales"))
                        .header("Authorization", "Bearer user-access-token")
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body())
                .contains("\"detail\":\"The cash management service is unavailable. Please try again later.\"")
                .contains("\"instance\":\"/api/v1/cash-management/companies/c1/sales\"")
                .doesNotContain("Exception");
    }

    private static String unusedPortUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
