package br.com.encurtador.analytics.api;

import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Limite das estatísticas do usuário, por usuário (ADR-0004, D7; ADR-0002, D5): a consulta varre os cliques de todos
 * os links dele.
 */
@Configuration(proxyBeanMethods = false)
class StatsRoutesConfig {

    @Bean
    RateLimitRule userStatsRateLimit() {
        return RateLimitRule.exact("user-stats", "GET", "/api/v1/stats", Key.USER);
    }
}
