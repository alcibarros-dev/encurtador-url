package br.com.encurtador.linkcache;

import java.time.Instant;

/**
 * O que o cache sabe sobre um código. Expirado e removido são tombstones: guardam só o estado, para que o 410 não
 * precise ir ao banco (ADR-0001, D7). Código inexistente não é cacheado.
 */
public sealed interface CachedLink {

    record Active(long linkId, String targetUrl, Instant expiresAt) implements CachedLink {
    }

    record Expired() implements CachedLink {
    }

    record Deleted() implements CachedLink {
    }
}
