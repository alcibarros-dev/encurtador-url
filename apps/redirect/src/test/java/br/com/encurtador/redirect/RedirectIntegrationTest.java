package br.com.encurtador.redirect;

import br.com.encurtador.linkcache.LinkCache;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedirectClient;
import br.com.encurtador.support.RedirectClient.Response;
import br.com.encurtador.support.RedirectTest;
import br.com.encurtador.support.TestLinks;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redirecionamento pela rota real do serviço, com o Redis e o PostgreSQL de verdade (ADR-0001, §2.1, D4 e D7;
 * cenários 2, 3 e 14 do D14; ADR-0006).
 */
@RedirectTest
class RedirectIntegrationTest {

    private static final String TARGET = "https://exemplo.com/artigo?id=42";

    @LocalServerPort
    int port;

    @Autowired
    TestLinks links;

    @Autowired
    LinkCache cache;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    private RedirectClient client;

    @BeforeEach
    void setUp() {
        client = new RedirectClient(port);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void activeLinkRedirectsWithFoundAndNoStore() {
        String code = links.active(TARGET).code();

        Response response = client.get("/" + code);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue(TARGET);
        assertThat(response.header("Cache-Control")).hasValue("private, no-store");
        assertThat(response.body().isMissingNode()).isTrue();
        assertThat(cached(code)).contains("\"status\":\"ACTIVE\"", "\"targetUrl\":\"" + TARGET + "\"");
        assertThat(redis.getExpire(key(code), TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofMinutes(10).toMillis());
    }

    @Test
    void nextRedirectIsServedFromTheCache() {
        String code = links.active(TARGET).code();
        client.get("/" + code);
        double hits = timerCount("found", "hit");

        // Muda o destino por baixo: se o banco fosse consultado, o Location mudaria.
        links.changeTarget(code, "https://outro.com/");

        assertThat(client.get("/" + code).header("Location")).hasValue(TARGET);
        assertThat(timerCount("found", "hit")).isEqualTo(hits + 1);
    }

    @Test
    void headIsAnsweredLikeGet() {
        String code = links.active(TARGET).code();

        Response response = client.head("/" + code);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue(TARGET);
        assertThat(response.header("Cache-Control")).hasValue("private, no-store");
    }

    @Test
    void unknownCodeIs404AndIsNotCached() {
        Response response = client.get("/naoExiste9");

        assertProblem(response, 404, "LINK_NOT_FOUND");
        assertThat(redis.hasKey(key("naoExiste9"))).isFalse();
    }

    @Test
    void browserAcceptingOnlyHtmlStillGetsTheProblemDetail() {
        Response response = client.get("/naoExiste9", "Accept", "text/html");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.text("code")).isEqualTo("LINK_NOT_FOUND");
    }

    @Test
    void pathsOutsideTheCodePatternAreNotRedirects() {
        assertProblem(client.get("/abc"), 404, "NOT_FOUND");
        assertProblem(client.get("/favicon.ico"), 404, "NOT_FOUND");
        assertProblem(client.get("/" + "a".repeat(33)), 404, "NOT_FOUND");
        assertProblem(client.get("/um/dois"), 404, "NOT_FOUND");
        // A API não existe aqui (ADR-0006, D1).
        assertProblem(client.get("/api/v1/links"), 404, "NOT_FOUND");
    }

    /** Regressão de P1: o tombstone que a API grava depois do commit já vale no redirect seguinte (ADR-0006, D4). */
    @Test
    void deletionTombstoneTakesEffectOnTheNextRedirect() {
        String code = links.active(TARGET).code();
        assertThat(client.get("/" + code).status()).isEqualTo(302);

        links.markDeleted(code);
        cache.markDeleted(code);

        assertProblem(client.get("/" + code), 410, "LINK_DELETED");
    }

    @Test
    void deletedLinkWithAColdCacheIsGoneAndBecomesATombstone() {
        String code = links.active(TARGET).code();
        links.markDeleted(code);

        assertProblem(client.get("/" + code), 410, "LINK_DELETED");
        assertThat(cached(code)).isEqualTo("{\"status\":\"DELETED\"}");
        assertThat(redis.getExpire(key(code), TimeUnit.MINUTES)).isBetween(59L, 60L);
    }

    /** Regressão de P3: a expiração vale no próximo acesso, inclusive com o cache quente, e nada é gravado. */
    @Test
    void expirationTakesEffectWithoutWritingToTheDatabase() {
        String code = links.create(TARGET, clock.instant().plus(Duration.ofMinutes(2))).code();
        assertThat(client.get("/" + code).status()).isEqualTo(302);

        clock.advance(Duration.ofMinutes(3));

        // A entrada ativa ainda está no Redis (o TTL dele corre no relógio real): o hit confere a validade.
        assertThat(cached(code)).contains("\"status\":\"ACTIVE\"");
        assertProblem(client.get("/" + code), 410, "LINK_EXPIRED");

        // Com o cache frio, a leitura do banco grava o tombstone de expiração.
        redis.delete(key(code));
        assertProblem(client.get("/" + code), 410, "LINK_EXPIRED");
        assertThat(cached(code)).isEqualTo("{\"status\":\"EXPIRED\"}");
        assertThat(jdbc.sql("select status from links where code = :code").param("code", code)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void cachedEntryNeverOutlivesTheLink() {
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(2));
        String code = links.create(TARGET, expiresAt).code();

        client.get("/" + code);

        assertThat(redis.getExpire(key(code), TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofMinutes(2).toMillis());
    }

    /** O pool da aplicação é só de leitura: o PostgreSQL recusa a escrita (ADR-0006, D2). */
    @Test
    void theDatabaseRefusesWritesFromTheService() {
        String code = links.active(TARGET).code();

        assertThatThrownBy(() -> jdbc.sql("update links set target_url = 'https://outro.com/' where code = :code")
                .param("code", code).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only");
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
