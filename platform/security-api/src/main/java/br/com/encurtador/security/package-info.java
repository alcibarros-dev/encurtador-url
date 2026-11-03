/**
 * Segurança do app (ADR-0002, D3 e D4): validação do token, regras de rota, respostas 401 e 403 e resolução do
 * usuário autenticado. Não conhece o provedor de identidade nem os módulos de negócio.
 * <p>
 * API pública, sem dependência de framework: {@link br.com.encurtador.security.CurrentUser},
 * {@link br.com.encurtador.security.UserIdResolver}, {@link br.com.encurtador.security.PublicRoutes} e
 * {@link br.com.encurtador.security.OpenApiSecurity}. Os subpacotes são internos.
 */
@ApplicationModule(displayName = "Security", allowedDependencies = "shared")
package br.com.encurtador.security;

import org.springframework.modulith.ApplicationModule;
