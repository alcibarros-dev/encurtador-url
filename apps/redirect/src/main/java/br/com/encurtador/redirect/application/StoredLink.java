package br.com.encurtador.redirect.application;

import java.time.Instant;

/**
 * Um link como está gravado na tabela {@code links} da API: só o que o redirecionamento precisa (ADR-0006, D2).
 *
 * @param deleted   status {@code DELETED}; {@code EXPIRED} não é gravado, é derivado de {@code expiresAt}
 */
public record StoredLink(long id, String targetUrl, boolean deleted, Instant expiresAt) {

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
