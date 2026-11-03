package br.com.encurtador.link.api;

import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Limite da criação de links, por usuário (ADR-0001, D11; ADR-0002, D5). */
@Configuration(proxyBeanMethods = false)
class LinkRoutesConfig {

    @Bean
    RateLimitRule linkCreationRateLimit() {
        return RateLimitRule.exact("link-creation", "POST", "/api/v1/links", Key.USER);
    }
}
