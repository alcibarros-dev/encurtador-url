package br.com.encurtador.redirect.application;

import br.com.encurtador.link.LinkLookup;
import br.com.encurtador.link.LinkSnapshot;
import br.com.encurtador.link.LinkStatus;
import br.com.encurtador.redirect.application.LinkCache.Outcome;
import br.com.encurtador.redirect.application.LinkCache.Read;
import br.com.encurtador.redirect.application.Resolution.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Decisões do cache-aside (ADR-0001, §2.1 e D7), com cache e consulta falsos. */
class LinkResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final String CODE = "aZ3kP9q";
    private static final String TARGET = "https://exemplo.com/a";
    private static final Duration MAX_TTL = Duration.ofMinutes(10);
    private static final Duration TOMBSTONE_TTL = Duration.ofHours(1);

    private final FakeCache cache = new FakeCache();
    private final FakeLookup lookup = new FakeLookup();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LinkResolver resolver = new LinkResolver(cache, lookup,
            new LinkCacheProperties(MAX_TTL, TOMBSTONE_TTL), meters, Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- hit ----

    @Test
    void hitRedirectsWithoutTouchingTheDatabase() {
        cache.next = Read.hit(new CachedLink.Active(7, TARGET, NOW.plusSeconds(60)));

        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Found(7, TARGET));
        assertThat(lookup.calls).isZero();
        assertTimed("found", "hit");
    }

    @Test
    void activeEntryPastItsExpirationIsGone() {
        cache.next = Read.hit(new CachedLink.Active(7, TARGET, NOW));

        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Gone(Reason.EXPIRED));
    }

    @Test
    void tombstonesAnswerGoneFromTheCache() {
        cache.next = Read.hit(new CachedLink.Expired());
        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Gone(Reason.EXPIRED));

        cache.next = Read.hit(new CachedLink.Deleted());
        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Gone(Reason.DELETED));

        assertThat(lookup.calls).isZero();
        assertTimed("gone", "hit", 2);
    }

    // ---- miss ----

    @Test
    void missFillsTheCacheCappedAtTheMaximumTtl() {
        lookup.add(LinkStatus.ACTIVE, NOW.plus(Duration.ofDays(30)));

        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Found(7, TARGET));
        assertThat(cache.puts).containsExactly(
                new Put(CODE, new CachedLink.Active(7, TARGET, NOW.plus(Duration.ofDays(30))), MAX_TTL));
        assertTimed("found", "miss");
    }

    @Test
    void activeEntryNeverOutlivesTheLink() {
        lookup.add(LinkStatus.ACTIVE, NOW.plus(Duration.ofMinutes(3)));

        resolver.resolve(CODE);

        assertThat(cache.puts).singleElement().extracting(Put::ttl).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void linkAboutToExpireIsNotCached() {
        lookup.add(LinkStatus.ACTIVE, NOW.plusNanos(500_000));

        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Found(7, TARGET));
        assertThat(cache.puts).isEmpty();
    }

    @Test
    void expiredAndDeletedLinksBecomeTombstones() {
        lookup.add(LinkStatus.ACTIVE, NOW);
        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Gone(Reason.EXPIRED));

        lookup.add(LinkStatus.DELETED, NOW.plus(Duration.ofDays(1)));
        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Gone(Reason.DELETED));

        assertThat(cache.puts).containsExactly(
                new Put(CODE, new CachedLink.Expired(), TOMBSTONE_TTL),
                new Put(CODE, new CachedLink.Deleted(), TOMBSTONE_TTL));
    }

    @Test
    void unknownCodeIsNotCached() {
        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.NotFound());
        assertThat(cache.puts).isEmpty();
        assertTimed("not_found", "miss");
    }

    // ---- Redis indisponível ----

    @Test
    void redisErrorFallsBackToTheDatabaseWithoutWriting() {
        cache.next = Read.ERROR;
        lookup.add(LinkStatus.ACTIVE, NOW.plus(Duration.ofDays(1)));

        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Found(7, TARGET));
        assertThat(cache.puts).isEmpty();
        assertTimed("found", "error");
    }

    @Test
    void openBreakerFallsBackToTheDatabaseWithoutWriting() {
        cache.next = Read.BYPASSED;
        lookup.add(LinkStatus.ACTIVE, NOW.plus(Duration.ofDays(1)));

        assertThat(resolver.resolve(CODE)).isEqualTo(new Resolution.Found(7, TARGET));
        assertThat(cache.puts).isEmpty();
        assertTimed("found", "bypassed");
    }

    private void assertTimed(String outcome, String cacheOutcome) {
        assertTimed(outcome, cacheOutcome, 1);
    }

    private void assertTimed(String outcome, String cacheOutcome, int count) {
        assertThat(meters.get("shortener.redirect").tag("outcome", outcome).tag("cache", cacheOutcome).timer().count())
                .isEqualTo(count);
    }

    private record Put(String code, CachedLink entry, Duration ttl) {
    }

    private static final class FakeCache implements LinkCache {

        Read next = Read.MISS;
        final List<Put> puts = new ArrayList<>();

        @Override
        public Read read(String code) {
            return next;
        }

        @Override
        public void putIfAbsent(String code, CachedLink entry, Duration ttl) {
            puts.add(new Put(code, entry, ttl));
        }

        @Override
        public void markDeleted(String code) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeLookup implements LinkLookup {

        final Map<String, LinkSnapshot> links = new HashMap<>();
        int calls;

        void add(LinkStatus status, Instant expiresAt) {
            links.put(CODE, new LinkSnapshot(7, CODE, TARGET, status, expiresAt));
        }

        @Override
        public Optional<LinkSnapshot> findByCode(String code) {
            calls++;
            return Optional.ofNullable(links.get(code));
        }

        @Override
        public Optional<LinkSnapshot> findOwned(String code, long ownerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<LinkSnapshot> ownedLinks(long ownerId) {
            throw new UnsupportedOperationException();
        }
    }
}
