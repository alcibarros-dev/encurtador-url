package br.com.encurtador.linkcache.redis;

import br.com.encurtador.linkcache.CachedLink;
import br.com.encurtador.linkcache.LinkCache.Outcome;
import br.com.encurtador.linkcache.LinkCache.Read;
import br.com.encurtador.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formato das entradas e semântica de escrita no Redis real (ADR-0001, D7). É o mesmo código no serviço de
 * redirecionamento, que lê e preenche (ADR-0006, D3).
 */
@IntegrationTest
class RedisLinkCacheTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-12-01T12:00:00Z");

    @Autowired
    RedisLinkCache cache;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    private final String code = "c" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    void storesTheFormatOfTheAdr() {
        cache.putIfAbsent(code, new CachedLink.Active(42, "https://exemplo.com/a", EXPIRES_AT), Duration.ofMinutes(10));

        assertThat(raw()).isEqualTo("{\"linkId\":42,\"targetUrl\":\"https://exemplo.com/a\",\"status\":\"ACTIVE\","
                + "\"expiresAt\":\"2026-12-01T12:00:00Z\"}");
        assertThat(redis.getExpire(RedisLinkCache.KEY_PREFIX + code, TimeUnit.MILLISECONDS))
                .isBetween(1L, Duration.ofMinutes(10).toMillis());
        assertThat(cache.read(code)).isEqualTo(Read.hit(new CachedLink.Active(42, "https://exemplo.com/a", EXPIRES_AT)));
    }

    @Test
    void tombstonesCarryOnlyTheStatus() {
        cache.putIfAbsent(code, new CachedLink.Expired(), Duration.ofHours(1));
        assertThat(raw()).isEqualTo("{\"status\":\"EXPIRED\"}");
        assertThat(cache.read(code)).isEqualTo(Read.hit(new CachedLink.Expired()));

        cache.markDeleted(code);
        assertThat(raw()).isEqualTo("{\"status\":\"DELETED\"}");
        assertThat(cache.read(code)).isEqualTo(Read.hit(new CachedLink.Deleted()));
    }

    @Test
    void missingKeyIsAMiss() {
        assertThat(cache.read(code)).isEqualTo(Read.MISS);
    }

    @Test
    void lateFillNeverReplacesTheDeletionTombstone() {
        // Leitor leu o link ativo no banco, a remoção gravou o tombstone e só então o leitor tenta preencher (D7).
        cache.markDeleted(code);

        cache.putIfAbsent(code, new CachedLink.Active(42, "https://exemplo.com/a", EXPIRES_AT), Duration.ofMinutes(10));

        assertThat(cache.read(code)).isEqualTo(Read.hit(new CachedLink.Deleted()));
    }

    @Test
    void deletionTombstoneReplacesAnActiveEntry() {
        cache.putIfAbsent(code, new CachedLink.Active(42, "https://exemplo.com/a", EXPIRES_AT), Duration.ofMinutes(10));

        cache.markDeleted(code);

        assertThat(cache.read(code)).isEqualTo(Read.hit(new CachedLink.Deleted()));
        assertThat(redis.getExpire(RedisLinkCache.KEY_PREFIX + code, TimeUnit.SECONDS))
                .isBetween(Duration.ofMinutes(59).toSeconds(), Duration.ofHours(1).toSeconds());
    }

    @Test
    void unreadableEntryIsTreatedAsAnErrorAndCounted() {
        double before = meters.counter("shortener.cache.errors", "op", "read").count();
        redis.opsForValue().set(RedisLinkCache.KEY_PREFIX + code, "{\"status\":\"ACTIVE\"}", Duration.ofMinutes(1));

        assertThat(cache.read(code).outcome()).isEqualTo(Outcome.ERROR);

        redis.opsForValue().set(RedisLinkCache.KEY_PREFIX + code, "não é json", Duration.ofMinutes(1));
        assertThat(cache.read(code).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(meters.counter("shortener.cache.errors", "op", "read").count()).isEqualTo(before + 2);
    }

    private String raw() {
        return redis.opsForValue().get(RedisLinkCache.KEY_PREFIX + code);
    }
}
