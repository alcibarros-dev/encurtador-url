package br.com.encurtador.redirect.api;

import br.com.encurtador.ratelimit.RateLimitFilter;
import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import br.com.encurtador.ratelimit.RateLimiter;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Limite do redirecionamento (ADR-0001, D11; ADR-0006, D6). O serviço não tem Spring Security: o filtro é registrado
 * aqui mesmo, e não depois de uma cadeia de segurança como na API.
 */
@Configuration(proxyBeanMethods = false)
class RedirectRoutesConfig {

    private static final Set<String> METHODS = Set.of("GET", "HEAD");

    /**
     * Todo {@code GET} e {@code HEAD} do serviço conta, e não só os de um segmento: sem o firewall do Spring Security,
     * uma variação de caminho ({@code //code}, {@code ;param}) escaparia de uma regra por padrão. As probes ficam de
     * fora no próprio filtro.
     */
    @Bean
    RateLimitRule redirectRateLimit() {
        return new RateLimitRule("redirect", request -> METHODS.contains(request.getMethod()), Key.IP);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, List<RateLimitRule> rules,
                                                            ProblemResponseWriter problems) {
        return new FilterRegistrationBean<>(new RateLimitFilter(limiter, rules, problems));
    }
}
