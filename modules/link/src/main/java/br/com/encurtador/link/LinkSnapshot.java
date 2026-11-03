package br.com.encurtador.link;

import java.time.Instant;

/**
 * Link visto de fora do módulo, para quem só precisa resolvê-lo: o redirecionamento e o analytics. Não diz quem é o
 * dono.
 *
 * @param status status gravado; {@code EXPIRED} é derivado com {@link #isExpiredAt(Instant)}
 */
public record LinkSnapshot(long id, String code, String targetUrl, LinkStatus status, Instant expiresAt) {

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
