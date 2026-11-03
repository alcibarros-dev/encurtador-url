package br.com.encurtador.analytics;

/** Porta de entrada dos cliques, usada pelo redirecionamento (ADR-0001, D8). */
public interface ClickRecorder {

    /**
     * Nunca bloqueia, nunca lança e nunca toca o Kafka (corrige P2). Com a fila cheia, o clique é descartado e
     * contado em {@code shortener.clicks.dropped}.
     */
    void offer(LinkClicked click);
}
