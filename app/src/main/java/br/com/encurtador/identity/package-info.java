/**
 * Contas de usuário, credenciais e sessões: cadastro, login, refresh com rotação, logout e troca de senha
 * (ADR-0001, D9).
 * <p>
 * É o provedor de identidade do app (transitório, ADR-0002): emite os access tokens, traduz o {@code sub} deles
 * ({@code UserIdResolver}) e declara as próprias rotas públicas e limites. Os refresh tokens são opacos e pertencem a
 * este módulo.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"security", "ratelimit", "shared"})
package br.com.encurtador.identity;

import org.springframework.modulith.ApplicationModule;
