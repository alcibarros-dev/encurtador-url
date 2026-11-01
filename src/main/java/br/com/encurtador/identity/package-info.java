/**
 * Contas de usuário, credenciais e sessões: cadastro, login, refresh com rotação, logout e troca de senha
 * (ADR-0001, D9).
 * <p>
 * Os access tokens são emitidos pelo {@code shared.security.AccessTokenIssuer}; os refresh tokens são opacos e
 * pertencem a este módulo.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = "shared")
package br.com.encurtador.identity;

import org.springframework.modulith.ApplicationModule;
