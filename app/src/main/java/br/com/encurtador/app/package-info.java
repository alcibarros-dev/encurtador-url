/**
 * Configuração da aplicação: o que junta módulos que não se conhecem (o filtro de rate limit depois da cadeia de
 * segurança) e a documentação OpenAPI, com o login pelo servidor de autorização no perfil local (ADR-0002, D1 e D5;
 * ADR-0003, D10).
 */
@ApplicationModule(displayName = "App", allowedDependencies = {"ratelimit", "security", "shared"})
package br.com.encurtador.app;

import org.springframework.modulith.ApplicationModule;
