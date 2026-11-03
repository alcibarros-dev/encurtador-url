package br.com.encurtador.link.api;

import br.com.encurtador.link.application.LinkView;

import java.time.Instant;

/** {@code status} é {@code ACTIVE} ou {@code EXPIRED}, calculado na leitura (ADR-0001, D12). */
record LinkResponse(String code, String shortUrl, String targetUrl, LinkView.Status status, Instant createdAt,
                    Instant expiresAt) {

    static LinkResponse from(LinkView link, String shortUrlPrefix) {
        return new LinkResponse(link.code(), shortUrlPrefix + link.code(), link.targetUrl(), link.status(),
                link.createdAt(), link.expiresAt());
    }
}
