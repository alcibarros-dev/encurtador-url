package br.com.encurtador.redirect.application;

/** Resultado da resolução de um código: 302, 410 ou 404 (ADR-0001, D4). */
public sealed interface Resolution {

    record Found(long linkId, String targetUrl) implements Resolution {
    }

    record Gone(Reason reason) implements Resolution {
    }

    record NotFound() implements Resolution {
    }

    enum Reason {
        EXPIRED,
        DELETED
    }
}
