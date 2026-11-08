package br.com.encurtador.redirect.application;

import br.com.encurtador.clicks.LinkClicked;

/** Porta de entrada dos cliques: o redirecionamento entrega, o {@code ClickSender} publica (ADR-0006, D5). */
public interface ClickRecorder {

    /**
     * Nunca bloqueia, nunca lança e nunca toca o Kafka (corrige P2). Com a fila cheia, o clique é descartado e
     * contado em {@code shortener.clicks.dropped}.
     */
    void offer(LinkClicked click);
}
