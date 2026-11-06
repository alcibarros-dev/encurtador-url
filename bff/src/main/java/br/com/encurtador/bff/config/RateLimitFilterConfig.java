package br.com.encurtador.bff.config;

import br.com.encurtador.ratelimit.RateLimitFilter;
import br.com.encurtador.ratelimit.RateLimitRule;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import br.com.encurtador.ratelimit.RateLimiter;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;

import java.util.List;

/**
 * Limite do início do login por IP (ADR-0004, D4 e R3): cada início cria uma sessão anônima em memória. O
 * redirecionamento para o provedor sai de dentro da cadeia do Spring Security, por isso o filtro vem antes dela.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitFilterConfig {

    private static final String AUTHORIZATION_PREFIX =
            OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/";

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, ProblemResponseWriter problems) {
        var rule = new RateLimitRule("login-start", RateLimitFilterConfig::isLoginStart, Key.IP);
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(limiter, List.of(rule), problems));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    static boolean isLoginStart(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod())
                && (path.startsWith(AUTHORIZATION_PREFIX) || "/bff/register".equals(path));
    }
}
