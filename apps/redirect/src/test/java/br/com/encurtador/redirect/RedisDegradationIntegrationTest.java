package br.com.encurtador.redirect;

import br.com.encurtador.linkcache.LinkCache;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.support.RedirectClient;
import br.com.encurtador.support.RedirectTest;
import br.com.encurtador.support.RedisProxy;
import br.com.encurtador.support.TestLinks;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O redirecionamento não depende do Redis: degrada, não falha (ADR-0001, RNF01, RNF02 e D7; cenários 4 e 5 do D14).
 * O Redis é derrubado e pendurado pelo Toxiproxy que fica entre ele e o serviço.
 */
@RedirectTest
class RedisDegradationIntegrationTest {

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
    TestLinks links;

    @Autowired
    MeterRegistry meters;

    private RedirectClient client;
    private String code;

    @BeforeEach
    void setUp() {
        client = new RedirectClient(port);
        code = links.active("https://exemplo.com/").code();
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
        assertThat(client.get("/" + code).status()).isEqualTo(302);
        redisProxy.cut();

        Duration slowest = Duration.ZERO;
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            assertThat(client.get("/" + code).status()).isEqualTo(302);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            slowest = elapsed.compareTo(slowest) > 0 ? elapsed : slowest;
        }
        // Sem conexão, o Lettuce recusa o comando na hora (REJECT_COMMANDS). Com o padrão, o primeiro comando depois
        // da queda ficaria na fila de reconexão e esperaria o timeout de 100 ms.
        assertThat(slowest).isLessThan(Duration.ofMillis(60));
        assertThat(guard.state()).isEqualTo(RedisGuard.State.OPEN);
        assertThat(meters.timer("shortener.redirect", "outcome", "found", "cache", "bypassed").count()).isPositive();
    }

    /** Cenário 5: com o disjuntor aberto, o p99 fica abaixo de 50 ms (RNF01). */
    @Test
    void hangingRedisOpensTheBreakerAndKeepsTheRedirectFast() {
        // Aquece o caminho (Tomcat, JDBC, Lettuce) com o Redis no ar: esta pode ser a primeira requisição da aplicação,
        // e o limite de 500 ms abaixo é do timeout do Redis, não da JVM fria. No runner do CI, a fria passava de 700 ms.
        assertThat(client.get("/" + code).status()).isEqualTo(302);
        redisProxy.hang();

        // Até o disjuntor abrir, cada requisição paga no máximo o timeout de 100 ms do comando.
        for (int i = 0; i < 20 && guard.state() != RedisGuard.State.OPEN; i++) {
            long start = System.nanoTime();
            assertThat(client.get("/" + code).status()).isEqualTo(302);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        }
        assertThat(guard.state()).isEqualTo(RedisGuard.State.OPEN);

        List<Duration> latencies = new ArrayList<>();
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            assertThat(client.get("/" + code).status()).isEqualTo(302);
            latencies.add(Duration.ofNanos(System.nanoTime() - start));
        }
        Collections.sort(latencies);
        Duration p99 = latencies.get((int) Math.ceil(MEASURED * 0.99) - 1);
        assertThat(p99).isLessThan(Duration.ofMillis(50));
    }
}
