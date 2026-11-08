/**
 * Configuração da aplicação: o que junta módulos que não se conhecem (o filtro de rate limit depois da cadeia de
 * segurança e o tombstone no cache do redirect quando um link é removido) e a documentação OpenAPI, com o login pelo
 * servidor de autorização no perfil local (ADR-0002, D1 e D5; ADR-0003, D10; ADR-0006, D4).
 */
@ApplicationModule(displayName = "App", allowedDependencies = {"link", "linkcache", "ratelimit", "shared"})
package br.com.encurtador.app;

import org.springframework.modulith.ApplicationModule;
