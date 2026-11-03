/**
 * Configuração da aplicação: o que junta módulos que não se conhecem (o filtro de rate limit depois da cadeia de
 * segurança) e a documentação OpenAPI (ADR-0002, D1 e D5).
 */
@ApplicationModule(displayName = "App", allowedDependencies = {"ratelimit", "shared"})
package br.com.encurtador.app;

import org.springframework.modulith.ApplicationModule;
