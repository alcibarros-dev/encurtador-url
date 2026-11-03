/**
 * Núcleo técnico: erros, configuração geral, tempo, disjuntor do Redis e validação (ADR-0002, D2).
 * <p>
 * Módulo aberto: os subpacotes são consumidos pelos demais módulos (ADR-0001, D2).
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package br.com.encurtador.shared;

import org.springframework.modulith.ApplicationModule;
