package br.com.encurtador.link.application;

import br.com.encurtador.link.domain.Link;

import java.time.Instant;

/**
 * Link como o dono o vê. O status é calculado no instante da leitura: {@code EXPIRED} nunca é gravado (ADR-0001, D5).
 * Links removidos não chegam aqui.
 */
public record LinkView(String code, String targetUrl, Status status, Instant createdAt, Instant expiresAt) {

    public enum Status {
        ACTIVE,
        EXPIRED
    }

    static LinkView of(Link link, Instant now) {
        Status status = link.isExpiredAt(now) ? Status.EXPIRED : Status.ACTIVE;
        return new LinkView(link.code(), link.targetUrl(), status, link.createdAt(), link.expiresAt());
    }
}
