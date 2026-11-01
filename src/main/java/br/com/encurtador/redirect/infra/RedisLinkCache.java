package br.com.encurtador.redirect.infra;

import br.com.encurtador.redirect.application.CachedLink;
import br.com.encurtador.redirect.application.LinkCache;
import br.com.encurtador.redirect.application.LinkCacheProperties;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.shared.redis.RedisGuard.Result;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Cache de resolução no Redis (ADR-0001, D7). Chave {@code link:v1:{code}}, valor JSON
 * {@code {linkId, targetUrl, status, expiresAt}}; os tombstones têm só {@code status}. O prefixo versionado permite
 * mudar o formato sem ler entradas antigas.
 */
@Component
class RedisLinkCache implements LinkCache {

    static final String KEY_PREFIX = "link:v1:";

    private static final Logger log = LoggerFactory.getLogger(RedisLinkCache.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final JsonMapper json;
    private final LinkCacheProperties properties;
    private final MeterRegistry meters;
    private final String deletedTombstone;

    RedisLinkCache(StringRedisTemplate redis, RedisGuard guard, JsonMapper json, LinkCacheProperties properties,
                   MeterRegistry meters) {
        this.redis = redis;
        this.guard = guard;
        this.json = json;
        this.properties = properties;
        this.meters = meters;
        this.deletedTombstone = json.writeValueAsString(Entry.of(new CachedLink.Deleted()));
    }

    @Override
    public Read read(String code) {
        return switch (guard.call(() -> redis.opsForValue().get(KEY_PREFIX + code))) {
            case Result.Done<String>(String value) when value == null -> Read.MISS;
            case Result.Done<String>(String value) -> decode(code, value);
            case Result.Failed<String> _ -> fail("read");
            case Result.Bypassed<String> _ -> Read.BYPASSED;
        };
    }

    @Override
    public void putIfAbsent(String code, CachedLink entry, Duration ttl) {
        String value = json.writeValueAsString(Entry.of(entry));
        if (guard.call(() -> redis.opsForValue().setIfAbsent(KEY_PREFIX + code, value, ttl))
                instanceof Result.Failed<Boolean>) {
            fail("write");
        }
    }

    @Override
    public void markDeleted(String code) {
        Result<Object> result = guard.call(() -> {
            redis.opsForValue().set(KEY_PREFIX + code, deletedTombstone, properties.tombstoneTtl());
            return null;
        });
        if (!(result instanceof Result.Done<Object>)) {
            // Disjuntor aberto também conta: a entrada ativa, se houver, só sai pelo TTL.
            fail("invalidate");
            log.warn("Cache não invalidado após remover o link (code={}): pode redirecionar por até {} s", code,
                    properties.maxTtl().toSeconds());
        }
    }

    private Read decode(String code, String value) {
        try {
            return Read.hit(json.readValue(value, Entry.class).toCachedLink());
        } catch (RuntimeException ex) {
            log.warn("Entrada ilegível no cache (code={}), tratada como ausente: {}", code, ex.toString());
            return fail("read");
        }
    }

    private Read fail(String operation) {
        meters.counter("shortener.cache.errors", "op", operation).increment();
        return Read.ERROR;
    }

    /** Formato gravado no Redis. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Entry(@Nullable Long linkId, @Nullable String targetUrl, Status status, @Nullable Instant expiresAt) {

        enum Status {
            ACTIVE,
            EXPIRED,
            DELETED
        }

        static Entry of(CachedLink link) {
            return switch (link) {
                case CachedLink.Active active ->
                        new Entry(active.linkId(), active.targetUrl(), Status.ACTIVE, active.expiresAt());
                case CachedLink.Expired _ -> new Entry(null, null, Status.EXPIRED, null);
                case CachedLink.Deleted _ -> new Entry(null, null, Status.DELETED, null);
            };
        }

        CachedLink toCachedLink() {
            return switch (Objects.requireNonNull(status, "status")) {
                case ACTIVE -> new CachedLink.Active(Objects.requireNonNull(linkId, "linkId"),
                        Objects.requireNonNull(targetUrl, "targetUrl"), Objects.requireNonNull(expiresAt, "expiresAt"));
                case EXPIRED -> new CachedLink.Expired();
                case DELETED -> new CachedLink.Deleted();
            };
        }
    }
}
