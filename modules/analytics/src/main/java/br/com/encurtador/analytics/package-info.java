/**
 * Consumo dos cliques e estatísticas (ADR-0001, D8). Analytics é eventual e tolerante a perdas pontuais: os cliques
 * chegam pelo tópico {@code link-clicks}, publicados pelo serviço de redirecionamento (ADR-0006, D5), no formato do
 * evento {@link br.com.encurtador.clicks.LinkClicked}. É o dono dos tópicos e da DLT.
 */
@ApplicationModule(displayName = "Analytics",
        allowedDependencies = {"clicks", "link", "ratelimit", "security", "shared"})
package br.com.encurtador.analytics;

import org.springframework.modulith.ApplicationModule;
