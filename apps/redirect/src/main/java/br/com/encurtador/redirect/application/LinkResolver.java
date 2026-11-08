package br.com.encurtador.redirect.application;

import br.com.encurtador.linkcache.CachedLink;
import br.com.encurtador.linkcache.LinkCache;
import br.com.encurtador.linkcache.LinkCacheProperties;
import br.com.encurtador.redirect.application.Resolution.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolve um código com cache-aside (ADR-0001, §2.1 e D7). Sem transação aberta: o caminho quente não segura conexão
 * do pool enquanto fala com o Redis.
 */
@Service
public class LinkResolver {

    private final LinkCache cache;
    private final LinkSource links;
    private final LinkCacheProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public LinkResolver(LinkCache cache, LinkSource links, LinkCacheProperties properties, MeterRegistry meters,
                        Clock clock) {
        this.cache = cache;
        this.links = links;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    public Resolution resolve(String code) {
        Timer.Sample sample = Timer.start(meters);
        Instant now = clock.instant();
        LinkCache.Read cached = cache.read(code);
        Resolution resolution = cached.entry() != null
                ? resolve(cached.entry(), now)
                : resolveFromDatabase(code, cached.outcome(), now);
        sample.stop(meters.timer("shortener.redirect",
                "outcome", outcomeTag(resolution), "cache", cached.outcome().tag()));
        return resolution;
    }

    private Resolution resolveFromDatabase(String code, LinkCache.Outcome cacheOutcome, Instant now) {
        Optional<StoredLink> link = links.findByCode(code);
        if (link.isEmpty()) {
            // Não é cacheado: uma varredura não repete códigos, e o lookup por índice único é barato (D7).
            return new Resolution.NotFound();
        }
        CachedLink entry = entryFor(link.get(), now);
        // Se o Redis acabou de falhar, uma escrita custaria outro timeout; com o disjuntor aberto, nem seria tentada.
        if (cacheOutcome == LinkCache.Outcome.MISS) {
            ttlFor(entry, now).ifPresent(ttl -> cache.putIfAbsent(code, entry, ttl));
        }
        return resolve(entry, now);
    }

    private static CachedLink entryFor(StoredLink link, Instant now) {
        if (link.deleted()) {
            return new CachedLink.Deleted();
        }
        if (link.isExpiredAt(now)) {
            return new CachedLink.Expired();
        }
        return new CachedLink.Active(link.id(), link.targetUrl(), link.expiresAt());
    }

    /** Uma entrada ativa nunca sobrevive ao link: o TTL é o menor entre o teto e o tempo até expirar. */
    private Optional<Duration> ttlFor(CachedLink entry, Instant now) {
        if (!(entry instanceof CachedLink.Active active)) {
            return Optional.of(properties.tombstoneTtl());
        }
        Duration untilExpiry = Duration.between(now, active.expiresAt());
        Duration ttl = untilExpiry.compareTo(properties.maxTtl()) < 0 ? untilExpiry : properties.maxTtl();
        // O Redis recusa TTL zero; um link a menos de 1 ms de expirar não vale a escrita.
        return ttl.toMillis() > 0 ? Optional.of(ttl) : Optional.empty();
    }

    /**
     * A validade é conferida também no hit: o TTL corre no relógio do Redis, e a expiração, no da aplicação
     * (defesa em profundidade, D7).
     */
    private static Resolution resolve(CachedLink entry, Instant now) {
        return switch (entry) {
            case CachedLink.Active active when active.expiresAt().isAfter(now) ->
                    new Resolution.Found(active.linkId(), active.targetUrl());
            case CachedLink.Active _, CachedLink.Expired _ -> new Resolution.Gone(Reason.EXPIRED);
            case CachedLink.Deleted _ -> new Resolution.Gone(Reason.DELETED);
        };
    }

    private static String outcomeTag(Resolution resolution) {
        return switch (resolution) {
            case Resolution.Found _ -> "found";
            case Resolution.Gone _ -> "gone";
            case Resolution.NotFound _ -> "not_found";
        };
    }
}
