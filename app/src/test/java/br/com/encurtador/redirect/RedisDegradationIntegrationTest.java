package br.com.encurtador.redirect;

import br.com.encurtador.identity.application.RegistrationService;
import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.redirect.application.LinkCache;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.RedisProxy;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O redirecionamento não depende do Redis: degrada, não falha (ADR-0001, RNF01, RNF02 e D7; cenários 4 e 5 do D14).
 * O Redis é derrubado e pendurado pelo Toxiproxy que fica entre ele e a aplicação.
 */
@IntegrationTest
class RedisDegradationIntegrationTest {

    private static final String TARGET = "https://exemplo.com/";
    private static final int MEASURED = 200;

    @LocalServerPort
    int port;

    @Autowired
    RedisProxy redisProxy;

    @Autowired
    RedisGuard guard;

    @Autowired
    LinkCache cache;

    @Autowired
    LinkService links;

    @Autowired
    RegistrationService registration;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    private ApiClient api;
    private long owner;
    private String code;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        owner = registration.register("degradacao-" + UUID.randomUUID() + "@exemplo.com", "senha-segura-1").id();
        code = links.create(owner, TARGET, null, null).code();
    }

    /** As outras classes de teste compartilham o contexto: o Redis e o disjuntor voltam ao normal. */
    @AfterEach
    void restoreRedis() {
        redisProxy.restore();
        String probe = "sonda-" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> cache.read(probe).outcome() == LinkCache.Outcome.MISS);
        assertThat(guard.state()).isEqualTo(RedisGuard.State.CLOSED);
    }

    /** Cenário 4: o Redis cai com a conexão do Lettuce já aberta. */
    @Test
    void redirectKeepsWorkingWithRedisDown() {
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);
        redisProxy.cut();

        Duration slowest = Duration.ZERO;
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            assertThat(api.get("/" + code, null).status()).isEqualTo(302);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            slowest = elapsed.compareTo(slowest) > 0 ? elapsed : slowest;
        }
        // Sem conexão, o Lettuce recusa o comando na hora (REJECT_COMMANDS). Com o padrão, o primeiro comando depois
        // da queda ficaria na fila de reconexão e esperaria o timeout de 100 ms.
        assertThat(slowest).isLessThan(Duration.ofMillis(60));
        assertThat(guard.state()).isEqualTo(RedisGuard.State.OPEN);
        assertThat(timerCount("bypassed")).isPositive();
    }

    /** Cenário 5: com o disjuntor aberto, o p99 fica abaixo de 50 ms (RNF01). */
    @Test
    void hangingRedisOpensTheBreakerAndKeepsTheRedirectFast() {
        redisProxy.hang();

        // Até o disjuntor abrir, cada requisição paga no máximo o timeout de 100 ms do comando.
        for (int i = 0; i < 20 && guard.state() != RedisGuard.State.OPEN; i++) {
            long start = System.nanoTime();
            assertThat(api.get("/" + code, null).status()).isEqualTo(302);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        }
        assertThat(guard.state()).isEqualTo(RedisGuard.State.OPEN);

        List<Duration> latencies = new ArrayList<>();
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            assertThat(api.get("/" + code, null).status()).isEqualTo(302);
            latencies.add(Duration.ofNanos(System.nanoTime() - start));
        }
        Collections.sort(latencies);
        Duration p99 = latencies.get((int) Math.ceil(MEASURED * 0.99) - 1);
        assertThat(p99).isLessThan(Duration.ofMillis(50));
    }

    @Test
    void deletionStillSucceedsWithRedisDownAndTheStalenessIsBounded() {
        api.get("/" + code, null);
        double failures = meters.counter("shortener.cache.errors", "op", "invalidate").count();
        redisProxy.cut();

        links.delete(owner, code);

        assertThat(meters.counter("shortener.cache.errors", "op", "invalidate").count()).isEqualTo(failures + 1);
        redisProxy.restore();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> cache.read(code).outcome() == LinkCache.Outcome.HIT);
        // A entrada ativa sobreviveu à remoção, mas sai pelo TTL: a defasagem máxima é app.cache.max-ttl.
        assertThat(redis.getExpire("link:v1:" + code, TimeUnit.MILLISECONDS))
                .isBetween(1L, Duration.ofMinutes(10).toMillis());
        redis.delete("link:v1:" + code);
    }

    private double timerCount(String cacheOutcome) {
        return meters.timer("shortener.redirect", "outcome", "found", "cache", cacheOutcome).count();
    }
}
