package br.com.encurtador.ratelimit;

import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedisProxy;
import br.com.encurtador.support.TestAccounts;
import br.com.encurtador.support.TestAccounts.TestAccount;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Limites pela cadeia HTTP real (ADR-0001, D11; cenário 16 do D14). Os testes compartilham o IP 127.0.0.1, então o
 * perfil de teste usa limites altíssimos; aqui o contador da janela é preenchido até o limite configurado, e a
 * requisição seguinte é a primeira acima dele. Os limites de login, cadastro e troca de senha são do auth-server
 * (ADR-0003, D9).
 */
@IntegrationTest
class RateLimitIntegrationTest {

    private static final String CLIENT_IP = "127.0.0.1";

    @LocalServerPort
    int port;

    @Autowired
    RateLimitProperties limits;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MutableClock clock;

    @Autowired
    TestAccounts accounts;

    @Autowired
    LinkService links;

    @Autowired
    RedisProxy redisProxy;

    @Autowired
    RedisGuard guard;

    @Autowired
    MeterRegistry meters;

    private ApiClient api;
    private final List<String> exhaustedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    /** O contador do IP de teste é compartilhado: um limite esgotado aqui barraria as classes seguintes. */
    private Limit limit(String rule) {
        return limits.rateLimit().get(rule);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(exhaustedKeys);
        clock.reset();
    }

    @Test
    void redirectAboveTheLimitGets429WithRetryAfterUntilTheWindowTurns() {
        startOfWindow(limit("redirect"));
        String code = links.create(accounts.create().id(), "https://exemplo.com/", null, null).code();
        double rejected = meters.counter("shortener.ratelimit.rejected", "rule", "redirect").count();
        exhaust("redirect", limit("redirect"), CLIENT_IP);

        Response limited = api.get("/" + code, null);

        assertRateLimited(limited, limit("redirect"));
        assertThat(meters.counter("shortener.ratelimit.rejected", "rule", "redirect").count()).isEqualTo(rejected + 1);
        clock.advance(limit("redirect").window());
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);
    }

    @Test
    void linkCreationIsLimitedPerUserAndOtherUsersAreUnaffected() {
        startOfWindow(limit("link-creation"));
        TestAccount user = accounts.create();
        // A chave do limite por usuário é o sub do token.
        exhaust("link-creation", limit("link-creation"), user.subject());

        Response limited = api.post("/api/v1/links", Map.of("url", "https://exemplo.com/"), user.token());
        Response other = api.post("/api/v1/links", Map.of("url", "https://exemplo.com/"), accounts.create().token());

        assertRateLimited(limited, limit("link-creation"));
        assertThat(other.status()).isEqualTo(201);
    }

    /** As estatísticas do usuário varrem os cliques de todos os links dele (ADR-0004, D7). */
    @Test
    void userStatsAreLimitedPerUser() {
        startOfWindow(limit("user-stats"));
        TestAccount user = accounts.create();
        exhaust("user-stats", limit("user-stats"), user.subject());

        assertRateLimited(api.get("/api/v1/stats", user.token()), limit("user-stats"));
        assertThat(api.get("/api/v1/stats", accounts.create().token()).status()).isEqualTo(200);
    }

    /** Cenário 16, parte do Redis fora: o limitador libera, e a requisição segue normalmente. */
    @Test
    void redisDownLetsRequestsThroughAndIsCounted() {
        String code = links.create(accounts.create().id(), "https://exemplo.com/", null, null).code();
        double failOpen = meters.counter("shortener.ratelimit.failopen", "rule", "redirect").count();
        redisProxy.cut();
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(api.get("/" + code, null).status()).isEqualTo(302);
            }
            assertThat(meters.counter("shortener.ratelimit.failopen", "rule", "redirect").count())
                    .isEqualTo(failOpen + 5);
        } finally {
            redisProxy.restore();
        }
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            api.get("/" + code, null);
            return guard.state() == RedisGuard.State.CLOSED;
        });
    }

    /** Leva o contador da janela atual ao limite configurado. */
    private void exhaust(String rule, Limit limit, String subject) {
        String key = RateLimiter.key(rule, subject, limit.window(), clock.instant());
        redis.opsForValue().set(key, Long.toString(limit.requests()), limit.window().plusMinutes(1));
        exhaustedKeys.add(key);
    }

    /**
     * Põe o relógio da aplicação no começo de uma janela, para que preencher o contador e fazer a requisição caiam
     * na mesma janela. Tokens são emitidos depois disso, para não expirarem com o avanço.
     */
    private void startOfWindow(Limit limit) {
        long windowMillis = limit.window().toMillis();
        clock.advance(Duration.ofMillis(windowMillis - clock.millis() % windowMillis + 100));
    }

    private static void assertRateLimited(Response response, Limit limit) {
        assertThat(response.status()).isEqualTo(429);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).hasValueSatisfying(value ->
                assertThat(Long.parseLong(value)).isBetween(1L, limit.window().toSeconds()));
    }
}
