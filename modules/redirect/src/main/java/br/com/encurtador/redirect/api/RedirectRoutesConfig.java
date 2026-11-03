package br.com.encurtador.redirect.api;

import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import br.com.encurtador.security.PublicRoutes;
import br.com.encurtador.security.PublicRoutes.Route;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/** Rota pública e limite do redirecionamento (ADR-0001, D4, D9 e D11; ADR-0002, D3 e D5). */
@Configuration(proxyBeanMethods = false)
class RedirectRoutesConfig {

    /** Um único segmento; o padrão do controller filtra o resto. */
    @Bean
    PublicRoutes redirectPublicRoutes() {
        return () -> List.of(Route.get("/*"), Route.head("/*"));
    }

    /** Varreduras com caminhos quaisquer de um segmento também contam. */
    @Bean
    RateLimitRule redirectRateLimit() {
        return RateLimitRule.singleSegment("redirect", Set.of("GET", "HEAD"), Key.IP);
    }
}
