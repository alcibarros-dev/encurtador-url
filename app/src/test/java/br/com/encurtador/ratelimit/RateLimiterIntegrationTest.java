package br.com.encurtador.ratelimit;

import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.ratelimit.RateLimiter.Decision;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedisProxy;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Janela fixa com o script Lua no Redis real (ADR-0001, D11). */
@IntegrationTest
class RateLimiterIntegrationTest {

    private static final Limit THREE_PER_MINUTE = new Limit(3, Duration.ofMinutes(1));

    @Autowired
    RateLimiter limiter;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MutableClock clock;

    @Autowired
    RedisProxy redisProxy;

    @Autowired
    RedisGuard guard;

    @Autowired
    MeterRegistry meters;

    private final String subject = "sujeito-" + UUID.randomUUID();

    @AfterEach
    void reset() {
        redisProxy.restore();
        clock.reset();
    }

    @Test
    void allowsUpToTheLimitAndThenRejectsUntilTheWindowTurns() {
        startOfWindow();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("teste", THREE_PER_MINUTE, subject)).isInstanceOf(Decision.Allowed.class);
        }

        Decision rejected = limiter.tryAcquire("teste", THREE_PER_MINUTE, subject);

        assertThat(rejected).isInstanceOfSatisfying(Decision.Rejected.class, decision ->
                assertThat(decision.retryAfter()).isBetween(Duration.ofSeconds(50), Duration.ofMinutes(1)));
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("teste", THREE_PER_MINUTE, subject)).isInstanceOf(Decision.Allowed.class);
    }

    @Test
    void subjectsAndRulesHaveSeparateCounters() {
        startOfWindow();
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("teste", THREE_PER_MINUTE, subject);
        }

        assertThat(limiter.tryAcquire("teste", THREE_PER_MINUTE, "outro-" + subject))
                .isInstanceOf(Decision.Allowed.class);
        assertThat(limiter.tryAcquire("outra-regra", THREE_PER_MINUTE, subject)).isInstanceOf(Decision.Allowed.class);
    }

    @Test
    void counterExpiresWithItsWindowAndDoesNotExposeTheSubject() {
        startOfWindow();
        limiter.tryAcquire("teste", THREE_PER_MINUTE, "fulano@exemplo.com");

        String key = RateLimiter.key("teste", "fulano@exemplo.com", THREE_PER_MINUTE.window(), clock.instant());
        assertThat(key).doesNotContain("fulano").matches("rl:teste:[0-9a-f]{32}:\\d+");
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        // O fim da janela mais a folga de 1 s.
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofSeconds(61).toMillis());
    }

    /** Cenário 16, parte do Redis fora: o limitador libera e conta. */
    @Test
    void failsOpenWithRedisDown() {
        double failOpen = meters.counter("shortener.ratelimit.failopen", "rule", "teste").count();
        redisProxy.cut();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("teste", THREE_PER_MINUTE, subject)).isInstanceOf(Decision.Allowed.class);
        }

        assertThat(meters.counter("shortener.ratelimit.failopen", "rule", "teste").count()).isEqualTo(failOpen + 5);
        redisProxy.restore();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            limiter.tryAcquire("sonda", THREE_PER_MINUTE, UUID.randomUUID().toString());
            return guard.state() == RedisGuard.State.CLOSED;
        });
    }

    /** Põe o relógio no começo de uma janela de 1 minuto: o teste não cruza a virada sem querer. */
    private void startOfWindow() {
        long windowMillis = Duration.ofMinutes(1).toMillis();
        clock.advance(Duration.ofMillis(windowMillis - clock.millis() % windowMillis + 100));
    }
}
