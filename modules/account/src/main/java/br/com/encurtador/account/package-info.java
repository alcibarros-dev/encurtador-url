/**
 * Contas do app (ADR-0003, D7): traduz o {@code sub} de um token válido no id local da conta, criando a conta no
 * primeiro acesso. O app não guarda e-mail nem senha: as credenciais são do servidor de autorização.
 * <p>
 * Não tem API pública: o módulo implementa o {@link br.com.authcore.security.UserIdResolver}, que o
 * {@code security} usa para resolver o {@code @CurrentUser}. Os links continuam apontando para o id da conta.
 */
@ApplicationModule(displayName = "Account", allowedDependencies = {})
package br.com.encurtador.account;

import org.springframework.modulith.ApplicationModule;
