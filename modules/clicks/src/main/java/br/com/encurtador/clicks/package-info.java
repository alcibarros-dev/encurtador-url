/**
 * Contrato do tópico {@code link-clicks} entre o serviço de redirecionamento, que publica, e o analytics da API, que
 * consome (ADR-0001, D8; ADR-0006, D3). Só o evento {@link br.com.encurtador.clicks.LinkClicked}: nenhum bean, nenhuma
 * regra de negócio.
 */
@ApplicationModule(displayName = "Clicks", allowedDependencies = {})
package br.com.encurtador.clicks;

import org.springframework.modulith.ApplicationModule;
