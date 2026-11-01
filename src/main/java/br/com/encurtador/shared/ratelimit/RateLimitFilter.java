package br.com.encurtador.shared.ratelimit;

import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import br.com.encurtador.shared.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.shared.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Aplica os limites do D11 por rota, logo depois do {@code BearerTokenAuthenticationFilter}: os limites por usuário
 * precisam do {@code sub}. Não é bean, para que o servlet container não o registre uma segunda vez fora da cadeia de
 * segurança.
 * <p>
 * O 429 é escrito aqui mesmo, em ProblemDetail: o {@code @RestControllerAdvice} não atua em filtros. O limite por
 * e-mail do login fica no {@code AuthService}, que já recebe o e-mail normalizado do corpo.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    /** Um segmento só: o padrão do redirecionamento. Varreduras com caminhos quaisquer também contam. */
    private static final Pattern SINGLE_SEGMENT = Pattern.compile("/[^/]+");
    /** Probes do orquestrador não podem ser barradas pelo limite do redirecionamento. */
    private static final Set<String> PROBES = Set.of("/livez", "/readyz");

    private final List<Rule> rules;
    private final RateLimiter limiter;
    private final ProblemResponseWriter problems;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties limits, ProblemResponseWriter problems) {
        this.limiter = limiter;
        this.problems = problems;
        Function<HttpServletRequest, String> byIp = request -> ClientAddress.subjectOf(request.getRemoteAddr());
        Function<HttpServletRequest, String> byUser = request -> authenticatedUser();
        this.rules = List.of(
                new Rule("login", limits.login(), exact("POST", "/api/v1/auth/login"), byIp),
                new Rule("register", limits.register(), exact("POST", "/api/v1/auth/register"), byIp),
                new Rule("refresh", limits.refresh(), exact("POST", "/api/v1/auth/refresh"), byIp),
                new Rule("password-change", limits.passwordChange(), exact("PUT", "/api/v1/users/me/password"),
                        byUser),
                new Rule("link-creation", limits.linkCreation(), exact("POST", "/api/v1/links"), byUser),
                new Rule("redirect", limits.redirect(), RateLimitFilter::isRedirect, byIp));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Rule rule : rules) {
            if (rule.matches().test(request)) {
                String subject = rule.subject().apply(request);
                // Sem usuário numa regra por usuário: a autorização vai devolver 401 logo adiante.
                if (subject != null
                        && limiter.tryAcquire(rule.name(), rule.limit(), subject)
                        instanceof RateLimiter.Decision.Rejected(var retryAfter)) {
                    reject(request, response, new RateLimitExceededException(retryAfter));
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitExceededException ex)
            throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        problems.write(request, response, ProblemDetails.of(HttpStatus.TOO_MANY_REQUESTS, ex.code(), ex.getMessage()));
    }

    private static @Nullable String authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token
                ? Long.toString(CurrentUser.id(token.getToken()))
                : null;
    }

    private static Predicate<HttpServletRequest> exact(String method, String path) {
        return request -> method.equals(request.getMethod()) && path.equals(pathOf(request));
    }

    private static boolean isRedirect(HttpServletRequest request) {
        String method = request.getMethod();
        String path = pathOf(request);
        return ("GET".equals(method) || "HEAD".equals(method))
                && SINGLE_SEGMENT.matcher(path).matches()
                && !PROBES.contains(path);
    }

    /**
     * Comparação literal é segura: o firewall do Spring Security já recusou {@code //}, {@code ;} e barras
     * codificadas, e o MVC não atende variações de caixa ou barra final.
     */
    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private record Rule(String name, Limit limit, Predicate<HttpServletRequest> matches,
                        Function<HttpServletRequest, @Nullable String> subject) {
    }
}
