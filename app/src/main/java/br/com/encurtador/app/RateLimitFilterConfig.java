package br.com.encurtador.app;

import br.com.encurtador.ratelimit.RateLimitFilter;
import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimiter;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registra o filtro de rate limit logo depois da cadeia do Spring Security (ADR-0002, D5). É a aplicação, e não o
 * módulo de segurança, quem junta os dois: o {@code security} não depende do {@code ratelimit}.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitFilterConfig {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, List<RateLimitRule> rules,
                                                            ProblemResponseWriter problems) {
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(limiter, rules, problems));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }
}
