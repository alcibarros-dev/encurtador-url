package br.com.encurtador.ratelimit;

import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Set;

/**
 * Aplica as regras de rate limit declaradas pelos módulos (ADR-0001, D11; ADR-0002, D5). A aplicação o registra como
 * filtro servlet depois da cadeia do Spring Security: o usuário autenticado chega como {@code getUserPrincipal()}, e
 * uma requisição já recusada com 401 ou 403 não conta no limite.
 * <p>
 * O 429 é escrito aqui mesmo, em ProblemDetail: o {@code @RestControllerAdvice} não atua em filtros. O limite por
 * e-mail do login fica no serviço de login, que já recebe o e-mail normalizado do corpo.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    /** Probes do orquestrador nunca são limitadas, nem pela regra de um segmento do redirecionamento. */
    private static final Set<String> PROBES = Set.of("/livez", "/readyz");

    private final List<BoundRule> rules;
    private final RateLimiter limiter;
    private final ProblemResponseWriter problems;

    public RateLimitFilter(RateLimiter limiter, List<RateLimitRule> rules, ProblemResponseWriter problems) {
        this.limiter = limiter;
        this.problems = problems;
        this.rules = rules.stream().map(rule -> new BoundRule(rule, limiter.limit(rule.name()))).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!PROBES.contains(RateLimitRule.pathOf(request))) {
            for (BoundRule bound : rules) {
                if (bound.rule().matches().test(request)) {
                    String subject = subjectOf(bound.rule().key(), request);
                    if (subject != null
                            && limiter.tryAcquire(bound.rule().name(), bound.limit(), subject)
                            instanceof RateLimiter.Decision.Rejected(var retryAfter)) {
                        reject(request, response, new RateLimitExceededException(retryAfter));
                        return;
                    }
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static @Nullable String subjectOf(RateLimitRule.Key key, HttpServletRequest request) {
        return switch (key) {
            case IP -> ClientAddress.subjectOf(request.getRemoteAddr());
            case USER -> {
                Principal user = request.getUserPrincipal();
                yield user == null ? null : user.getName();
            }
        };
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitExceededException ex)
            throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        problems.write(request, response, ProblemDetails.of(HttpStatus.TOO_MANY_REQUESTS, ex.code(), ex.getMessage()));
    }

    private record BoundRule(RateLimitRule rule, Limit limit) {
    }
}
