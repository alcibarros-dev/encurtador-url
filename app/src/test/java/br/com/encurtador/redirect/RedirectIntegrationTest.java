package br.com.encurtador.redirect;

import br.com.encurtador.identity.application.RegistrationService;
import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redirecionamento pela rota real, com o Redis de verdade (ADR-0001, §2.1, D4 e D7; cenários 2, 3 e 14 do D14).
 */
@IntegrationTest
class RedirectIntegrationTest {

    private static final String TARGET = "https://exemplo.com/artigo?id=42";

    @LocalServerPort
    int port;

    @Autowired
    LinkService links;

    @Autowired
    RegistrationService registration;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    private ApiClient api;
    private long owner;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        owner = registration.register("redirect-" + UUID.randomUUID() + "@exemplo.com", "senha-segura-1").id();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void activeLinkRedirectsWithFoundAndNoStore() {
        String code = newLink();

        Response response = api.get("/" + code, null);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue(TARGET);
        assertThat(response.header("Cache-Control")).hasValue("private, no-store");
        assertThat(response.body().isMissingNode()).isTrue();
        assertThat(cached(code)).contains("\"status\":\"ACTIVE\"", "\"targetUrl\":\"" + TARGET + "\"");
        assertThat(redis.getExpire(key(code), TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofMinutes(10).toMillis());
    }

    @Test
    void nextRedirectIsServedFromTheCache() {
        String code = newLink();
        api.get("/" + code, null);
        double hits = timerCount("found", "hit");

        // Muda o destino por baixo: se o banco fosse consultado, o Location mudaria.
        jdbc.sql("update links set target_url = 'https://outro.com/' where code = :code").param("code", code).update();

        assertThat(api.get("/" + code, null).header("Location")).hasValue(TARGET);
        assertThat(timerCount("found", "hit")).isEqualTo(hits + 1);
    }

    @Test
    void headIsAnsweredLikeGet() {
        String code = newLink();

        Response response = api.head("/" + code);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue(TARGET);
        assertThat(response.header("Cache-Control")).hasValue("private, no-store");
    }

    @Test
    void unknownCodeIs404AndIsNotCached() {
        Response response = api.get("/naoExiste9", null);

        assertProblem(response, 404, "LINK_NOT_FOUND");
        assertThat(redis.hasKey(key("naoExiste9"))).isFalse();
    }

    @Test
    void browserAcceptingOnlyHtmlStillGetsTheProblemDetail() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/naoExiste9"))
                        .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"LINK_NOT_FOUND\"");
    }

    @Test
    void pathsOutsideTheCodePatternAreNotRedirects() {
        assertProblem(api.get("/abc", null), 404, "NOT_FOUND");
        assertProblem(api.get("/favicon.ico", null), 404, "NOT_FOUND");
        assertProblem(api.get("/" + "a".repeat(33), null), 404, "NOT_FOUND");
        assertThat(api.get("/um/dois", null).status()).isEqualTo(401);
    }

    /** Regressão de P1: com o cache quente, o redirect seguinte à remoção já devolve 410. */
    @Test
    void deletionWithAWarmCacheTakesEffectOnTheNextRedirect() {
        String code = newLink();
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);

        links.delete(owner, code);

        assertProblem(api.get("/" + code, null), 410, "LINK_DELETED");
        assertThat(cached(code)).isEqualTo("{\"status\":\"DELETED\"}");
        assertThat(redis.getExpire(key(code), TimeUnit.MINUTES)).isBetween(59L, 60L);
    }

    @Test
    void rolledBackDeletionLeavesTheCacheUntouched() {
        String code = newLink();
        api.get("/" + code, null);

        transaction.executeWithoutResult(status -> {
            links.delete(owner, code);
            status.setRollbackOnly();
        });

        assertThat(cached(code)).contains("\"status\":\"ACTIVE\"");
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);
    }

    /** Regressão de P3: a expiração vale no próximo acesso, inclusive com o cache quente, e nada é gravado. */
    @Test
    void expirationTakesEffectWithoutWritingToTheDatabase() {
        String code = links.create(owner, TARGET, null, clock.instant().plus(Duration.ofMinutes(2))).code();
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);

        clock.advance(Duration.ofMinutes(3));

        // A entrada ativa ainda está no Redis (o TTL dele corre no relógio real): o hit confere a validade.
        assertThat(cached(code)).contains("\"status\":\"ACTIVE\"");
        assertProblem(api.get("/" + code, null), 410, "LINK_EXPIRED");

        // Com o cache frio, a leitura do banco grava o tombstone de expiração.
        redis.delete(key(code));
        assertProblem(api.get("/" + code, null), 410, "LINK_EXPIRED");
        assertThat(cached(code)).isEqualTo("{\"status\":\"EXPIRED\"}");
        assertThat(jdbc.sql("select status from links where code = :code").param("code", code)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void cachedEntryNeverOutlivesTheLink() {
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(2));
        String code = links.create(owner, TARGET, null, expiresAt).code();

        api.get("/" + code, null);

        assertThat(redis.getExpire(key(code), TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofMinutes(2).toMillis());
    }

    private String newLink() {
        return links.create(owner, TARGET, null, null).code();
    }

    private String cached(String code) {
        return redis.opsForValue().get(key(code));
    }

    private static String key(String code) {
        return "link:v1:" + code;
    }

    private double timerCount(String outcome, String cache) {
        return meters.timer("shortener.redirect", "outcome", outcome, "cache", cache).count();
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo(code);
    }
}
