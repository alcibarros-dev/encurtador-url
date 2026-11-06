package br.com.encurtador.auth.config;

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
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.function.Predicate;

/**
 * Limites dos endpoints de protocolo (ADR-0003, D9; ADR-0004, fase WA). Eles são respondidos dentro da cadeia do
 * Spring Security, por isso o filtro vem antes dela. Os limites do login, do cadastro e da troca de senha ficam no
 * provider do login e nos controllers, que mostram a espera na página em vez de um 429 em JSON.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitFilterConfig {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter, ProblemResponseWriter problems) {
        Predicate<HttpServletRequest> token = post("/oauth2/token");
        List<RateLimitRule> rules = List.of(
                // Cliente confidencial (o BFF do painel) por client_id: todas as trocas dele saem do mesmo IP.
                new RateLimitRule("token-client", token.and(RateLimitFilterConfig::hasBasicAuthentication), Key.CLIENT),
                new RateLimitRule("token", token.and(request -> !hasBasicAuthentication(request)), Key.IP),
                new RateLimitRule("device-authorization", post("/oauth2/device_authorization"), Key.IP),
                new RateLimitRule("device-verification", post("/oauth2/device_verification"), Key.IP));
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(limiter, rules, problems));
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    private static Predicate<HttpServletRequest> post(String path) {
        return RateLimitRule.matching("POST", path);
    }

    private static boolean hasBasicAuthentication(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }
}
