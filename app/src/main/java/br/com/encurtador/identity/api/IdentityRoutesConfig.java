package br.com.encurtador.identity.api;

import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import br.com.encurtador.security.PublicRoutes;
import br.com.encurtador.security.PublicRoutes.Route;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Rotas públicas e limites das rotas deste módulo (ADR-0001, D9 e D11; ADR-0002, D3 e D5). O limite de login por
 * e-mail fica no {@code AuthService}.
 */
@Configuration(proxyBeanMethods = false)
class IdentityRoutesConfig {

    /** Refresh e logout se autenticam pelo refresh token no corpo, não por Bearer. */
    @Bean
    PublicRoutes identityPublicRoutes() {
        return () -> List.of(
                Route.post("/api/v1/auth/register"),
                Route.post("/api/v1/auth/login"),
                Route.post("/api/v1/auth/refresh"),
                Route.post("/api/v1/auth/logout"));
    }

    @Bean
    RateLimitRule loginRateLimit() {
        return RateLimitRule.exact("login", "POST", "/api/v1/auth/login", Key.IP);
    }

    @Bean
    RateLimitRule registerRateLimit() {
        return RateLimitRule.exact("register", "POST", "/api/v1/auth/register", Key.IP);
    }

    @Bean
    RateLimitRule refreshRateLimit() {
        return RateLimitRule.exact("refresh", "POST", "/api/v1/auth/refresh", Key.IP);
    }

    /** Por usuário: força bruta da senha atual com um token roubado. */
    @Bean
    RateLimitRule passwordChangeRateLimit() {
        return RateLimitRule.exact("password-change", "PUT", "/api/v1/users/me/password", Key.USER);
    }
}
