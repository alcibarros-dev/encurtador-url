package br.com.encurtador.shared.security;

import br.com.encurtador.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cadeia de segurança, probes e porta de gestão com a aplicação real (ADR-0001, D9 e D13).
 */
@IntegrationTest
class SecurityIntegrationTest {

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    AccessTokenIssuer tokenIssuer;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void probesArePublicOnTheApplicationPort() throws Exception {
        HttpResponse<String> liveness = get(port, "/livez", null);
        HttpResponse<String> readiness = get(port, "/readyz", null);

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).doesNotContain("components");
    }

    @Test
    void fullActuatorIsServedOnlyOnTheManagementPort() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health", null);
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus", null);
        HttpResponse<String> healthOnPublicPort = get(port, "/actuator/health", null);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"db\"", "\"redis\"", "\"kafkaPublish\"");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory_used_bytes");
        assertThat(healthOnPublicPort.statusCode()).isNotEqualTo(200);
    }

    @Test
    void apiRequiresBearerToken() throws Exception {
        HttpResponse<String> response = get(port, "/api/v1/qualquer", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).startsWith("Bearer"));
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"", "\"status\":401",
                "\"title\":\"Não autenticado\"", "\"instance\":\"/api/v1/qualquer\"");
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        String token = tokenIssuer.issue(1L).value();

        HttpResponse<String> response = get(port, "/api/v1/qualquer", token);

        // Autenticado; a rota ainda não existe e o erro sai em ProblemDetail.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void tamperedTokenIsRejectedWith401NotWith500() throws Exception {
        String[] parts = tokenIssuer.issue(1L).value().split("\\.");
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"1\"", "\"sub\":\"2\"");
        String tampered = parts[0] + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        HttpResponse<String> response = get(port, "/api/v1/qualquer", tampered);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).contains("error=\"invalid_token\""));
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
    }

    @Test
    void routesNotExplicitlyAllowedAreDenied() throws Exception {
        assertThat(get(port, "/qualquer/coisa", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/v3/api-docs", null).statusCode()).isEqualTo(401);
    }

    @Test
    void protectedResourceMetadataDoesNotClaimMutualTls() throws Exception {
        HttpResponse<String> response = get(port, "/.well-known/oauth-protected-resource", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"tls_client_certificate_bound_access_tokens\":false",
                "\"resource_name\":\"Encurtador de URLs\"", "\"bearer_methods_supported\":[\"header\"]");
    }

    @Test
    void authenticatedUserOnADeniedRouteGets403ProblemDetail() throws Exception {
        HttpResponse<String> response = get(port, "/qualquer/coisa", tokenIssuer.issue(1L).value());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"", "\"title\":\"Acesso negado\"");
    }

    private HttpResponse<String> get(int targetPort, String path, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
