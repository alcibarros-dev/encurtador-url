package br.com.encurtador.security;

import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.TestJwks;
import br.com.encurtador.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cadeia de segurança, probes e porta de gestão com a aplicação real (ADR-0001, D9 e D13). Os tokens são validados
 * pelo JWKS de teste, pelo mesmo caminho de produção (ADR-0003, D6 e D12); os casos de cache, limite de buscas e
 * indisponibilidade do JWKS estão no teste do decodificador, no {@code platform/security}.
 */
@IntegrationTest
class SecurityIntegrationTest {

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestJwks jwks;

    @Autowired
    MutableClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        jwks.restore();
        clock.reset();
    }

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
        assertThat(health.body()).contains("\"db\"", "\"redis\"");
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

    /** O redirecionamento é do serviço apps/redirect: aqui, a raiz é uma rota protegida como as outras (ADR-0006). */
    @Test
    void shortCodesAreNotRedirectedByTheApi() throws Exception {
        assertThat(get(port, "/aZ3kP9q", null).statusCode()).isEqualTo(401);
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        HttpResponse<String> response = get(port, "/api/v1/qualquer", tokens.accessToken("assunto-1"));

        // Autenticado; a rota ainda não existe e o erro sai em ProblemDetail.
        assertAuthenticated(response);
    }

    /** O auth-server não grava o typ; outros emissores usam JWT ou at+jwt (ADR-0003, D5 e D6). */
    @ParameterizedTest
    @ValueSource(strings = {"JWT", "at+jwt"})
    void accessTokenTypesAreAccepted(String type) throws Exception {
        assertAuthenticated(get(port, "/api/v1/qualquer", tokens.token("assunto-1").type(type).sign()));
    }

    @Test
    void tokenFromAnotherIssuerOrForAnotherAudienceIsRejected() throws Exception {
        assertInvalidToken(get(port, "/api/v1/qualquer", tokens.token("assunto-1").issuer("http://outro.test").sign()));
        assertInvalidToken(get(port, "/api/v1/qualquer", tokens.token("assunto-1").audience("encurtador-web").sign()));
    }

    @Test
    void expiredTokenIsRejectedByTheApplicationClock() throws Exception {
        String token = tokens.accessToken("assunto-1");
        clock.advance(Duration.ofMinutes(16));

        assertInvalidToken(get(port, "/api/v1/qualquer", token));
    }

    /** Chave que o emissor não publica: 401, e não 503, mesmo depois de buscar o JWKS de novo. */
    @Test
    void tokenSignedByAnUnpublishedKeyIsRejected() throws Exception {
        String token = tokens.token("assunto-1").signedWith(TestJwks.rsaKey("desconhecida")).sign();

        assertInvalidToken(get(port, "/api/v1/qualquer", token));
    }

    /** Rotação (ADR-0003, D5): o JWKS passa a publicar a chave nova e a anterior, e o app aprende a nova sozinho. */
    @Test
    void rotatedKeyIsLearnedFromTheJwks() throws Exception {
        assertAuthenticated(get(port, "/api/v1/qualquer", tokens.accessToken("assunto-1")));
        RSAKey rotated = TestJwks.rsaKey("test-2");
        jwks.publish(rotated, TestJwks.KEY);
        int fetches = jwks.requests();

        assertAuthenticated(get(port, "/api/v1/qualquer", tokens.token("assunto-1").signedWith(rotated).sign()));
        assertAuthenticated(get(port, "/api/v1/qualquer", tokens.accessToken("assunto-1")));
        assertThat(jwks.requests()).isEqualTo(fetches + 1);
    }

    /** Com o auth-server fora, os tokens assinados por chaves já conhecidas continuam valendo (ADR-0003, D6). */
    @Test
    void knownKeysKeepWorkingWithTheAuthorizationServerDown() throws Exception {
        assertAuthenticated(get(port, "/api/v1/qualquer", tokens.accessToken("assunto-1")));
        jwks.down();

        assertAuthenticated(get(port, "/api/v1/qualquer", tokens.accessToken("assunto-2")));
    }

    @Test
    void tamperedTokenIsRejectedWith401NotWith500() throws Exception {
        String[] parts = tokens.accessToken("1").split("\\.");
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
        HttpResponse<String> response = get(port, "/qualquer/coisa", tokens.accessToken("assunto-1"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"", "\"title\":\"Acesso negado\"");
    }

    private static void assertAuthenticated(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
    }

    private static void assertInvalidToken(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).contains("error=\"invalid_token\""));
        assertThat(response.body()).contains("\"code\":\"INVALID_TOKEN\"");
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
