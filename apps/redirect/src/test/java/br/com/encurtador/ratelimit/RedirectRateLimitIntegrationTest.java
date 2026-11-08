package br.com.encurtador.ratelimit;

import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedirectClient;
import br.com.encurtador.support.RedirectClient.Response;
import br.com.encurtador.support.RedirectTest;
import br.com.encurtador.support.RedisProxy;
import br.com.encurtador.support.TestLinks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Limite do redirecionamento pela cadeia HTTP real (ADR-0001, D11; cenário 16 do D14; ADR-0006, D6). Os testes
 * compartilham o IP 127.0.0.1, então o perfil de teste usa um limite altíssimo; aqui o contador da janela é preenchido
 * até o limite configurado, e a requisição seguinte é a primeira acima dele.
 */
@RedirectTest
class RedirectRateLimitIntegrationTest {

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
    TestLinks links;

    @Autowired
    RedisProxy redisProxy;

    @Autowired
    RedisGuard guard;

    @Autowired
    MeterRegistry meters;

    private RedirectClient client;
    private final List<String> exhaustedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = new RedirectClient(port);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(exhaustedKeys);
        clock.reset();
    }

    @Test
    void redirectAboveTheLimitGets429WithRetryAfterUntilTheWindowTurns() {
        Limit limit = limits.rateLimit().get("redirect");
        startOfWindow(limit);
        String code = links.active("https://exemplo.com/").code();
        double rejected = meters.counter("shortener.ratelimit.rejected", "rule", "redirect").count();
        exhaust(limit);

        assertRateLimited(client.get("/" + code), limit);
        assertThat(meters.counter("shortener.ratelimit.rejected", "rule", "redirect").count()).isEqualTo(rejected + 1);
        // Qualquer GET conta, não só os de um segmento (D6); as probes, nunca.
        assertRateLimited(client.get("/um/dois"), limit);
        assertRateLimited(client.head("/" + code), limit);
        assertThat(client.get("/readyz").status()).isEqualTo(200);

        clock.advance(limit.window());
        assertThat(client.get("/" + code).status()).isEqualTo(302);
    }

    /** Cenário 16, parte do Redis fora: o limitador libera, e a requisição segue normalmente. */
    @Test
    void redisDownLetsRequestsThroughAndIsCounted() {
        String code = links.active("https://exemplo.com/").code();
        double failOpen = meters.counter("shortener.ratelimit.failopen", "rule", "redirect").count();
        redisProxy.cut();
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(client.get("/" + code).status()).isEqualTo(302);
            }
            assertThat(meters.counter("shortener.ratelimit.failopen", "rule", "redirect").count())
                    .isEqualTo(failOpen + 5);
        } finally {
            redisProxy.restore();
        }
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            client.get("/" + code);
            return guard.state() == RedisGuard.State.CLOSED;
        });
    }

    /** Leva o contador da janela atual ao limite configurado. */
    private void exhaust(Limit limit) {
        String key = RateLimiter.key("redirect", CLIENT_IP, limit.window(), clock.instant());
        redis.opsForValue().set(key, Long.toString(limit.requests()), limit.window().plusMinutes(1));
        exhaustedKeys.add(key);
    }

    /** Põe o relógio no começo de uma janela, para que preencher o contador e a requisição caiam na mesma janela. */
    private void startOfWindow(Limit limit) {
        long windowMillis = limit.window().toMillis();
        clock.advance(Duration.ofMillis(windowMillis - clock.millis() % windowMillis + 100));
    }

    private static void assertRateLimited(Response response, Limit limit) {
        assertThat(response.status()).isEqualTo(429);
        assertThat(response.header("Retry-After")).hasValueSatisfying(value ->
                assertThat(Long.parseLong(value)).isBetween(1L, limit.window().toSeconds()));
        if (!response.body().isMissingNode()) {
            assertThat(response.header("Content-Type")).hasValue("application/problem+json");
            assertThat(response.text("code")).isEqualTo("RATE_LIMITED");
        }
    }
}
