package br.com.encurtador.app;

import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.linkcache.CachedLink;
import br.com.encurtador.linkcache.LinkCache;
import br.com.encurtador.linkcache.LinkCache.Read;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedisProxy;
import br.com.encurtador.support.TestAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A API grava o tombstone de remoção no Redis que o serviço de redirecionamento lê (ADR-0001, §2.3 e D7; ADR-0006,
 * D4). O cache quente é simulado com a entrada que o redirect gravaria; o efeito no redirect é testado nele.
 */
@IntegrationTest
class LinkCacheInvalidationIntegrationTest {

    private static final String TARGET = "https://exemplo.com/";

    @Autowired
    LinkService links;

    @Autowired
    TestAccounts accounts;

    @Autowired
    LinkCache cache;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    RedisProxy redisProxy;

    @Autowired
    RedisGuard guard;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MutableClock clock;

    private long owner;
    private String code;

    @BeforeEach
    void setUp() {
        owner = accounts.create().id();
        code = links.create(owner, TARGET, null, null).code();
        // O que o redirect grava no primeiro acesso.
        cache.putIfAbsent(code, new CachedLink.Active(1, TARGET, clock.instant().plus(Duration.ofDays(1))),
                Duration.ofMinutes(10));
    }

    /** Regressão de P1: com o cache quente, a remoção troca a entrada ativa pelo tombstone. */
    @Test
    void deletionReplacesTheActiveEntryWithATombstone() {
        links.delete(owner, code);

        assertThat(cache.read(code)).isEqualTo(Read.hit(new CachedLink.Deleted()));
        assertThat(redis.opsForValue().get(key())).isEqualTo("{\"status\":\"DELETED\"}");
        assertThat(redis.getExpire(key(), TimeUnit.MINUTES)).isBetween(59L, 60L);
    }

    @Test
    void rolledBackDeletionLeavesTheCacheUntouched() {
        transaction.executeWithoutResult(status -> {
            links.delete(owner, code);
            status.setRollbackOnly();
        });

        assertThat(redis.opsForValue().get(key())).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void deletionStillSucceedsWithRedisDownAndTheStalenessIsBounded() {
        double failures = meters.counter("shortener.cache.errors", "op", "invalidate").count();
        redisProxy.cut();
        try {
            links.delete(owner, code);

            assertThat(meters.counter("shortener.cache.errors", "op", "invalidate").count()).isEqualTo(failures + 1);
        } finally {
            redisProxy.restore();
        }
        String probe = "sonda-" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> cache.read(probe).outcome() == LinkCache.Outcome.MISS);
        assertThat(guard.state()).isEqualTo(RedisGuard.State.CLOSED);
        // A entrada ativa sobreviveu à remoção, mas sai pelo TTL: a defasagem máxima é app.cache.max-ttl.
        assertThat(redis.opsForValue().get(key())).contains("\"status\":\"ACTIVE\"");
        assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofMinutes(10).toMillis());
        redis.delete(key());
    }

    private String key() {
        return "link:v1:" + code;
    }
}
