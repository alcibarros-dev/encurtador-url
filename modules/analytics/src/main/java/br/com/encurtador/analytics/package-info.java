/**
 * Cliques e estatísticas (ADR-0001, D8). Analytics é eventual e tolerante a perdas pontuais: o redirecionamento só
 * entrega o clique a uma fila em memória e nunca espera pelo Kafka.
 * <p>
 * API pública: {@link br.com.encurtador.analytics.ClickRecorder} e o evento
 * {@link br.com.encurtador.analytics.LinkClicked}.
 */
@ApplicationModule(displayName = "Analytics", allowedDependencies = {"link", "security", "shared"})
package br.com.encurtador.analytics;

import org.springframework.modulith.ApplicationModule;
