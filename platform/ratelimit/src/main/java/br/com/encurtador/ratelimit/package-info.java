/**
 * Rate limit em janela fixa no Redis (ADR-0001, D11; ADR-0002, D5): o limitador, o filtro servlet e o modelo de regra.
 * Cada módulo declara as regras das próprias rotas ({@link br.com.encurtador.ratelimit.RateLimitRule}); quem registra
 * o filtro é a aplicação, depois da cadeia do Spring Security.
 */
@ApplicationModule(displayName = "Rate limit", allowedDependencies = "shared")
package br.com.encurtador.ratelimit;

import org.springframework.modulith.ApplicationModule;
