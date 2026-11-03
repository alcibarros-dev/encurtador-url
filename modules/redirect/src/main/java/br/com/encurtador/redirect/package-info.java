/**
 * Redirecionamento {@code GET|HEAD /{code}}: o caminho quente (ADR-0001, §2.1, D4 e D7).
 * <p>
 * Resolve o código com cache-aside no Redis e nunca depende dele: erro, timeout ou disjuntor aberto viram leitura
 * direta do banco. É o dono do cache, e por isso quem o invalida quando um link é removido. Cada {@code GET} que
 * redireciona entrega um clique ao analytics, sem esperar por ele.
 */
@ApplicationModule(displayName = "Redirect", allowedDependencies = {"link", "analytics", "security", "ratelimit", "shared"})
package br.com.encurtador.redirect;

import org.springframework.modulith.ApplicationModule;
