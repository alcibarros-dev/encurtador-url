package br.com.encurtador.ratelimit;

import br.com.encurtador.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.ratelimit.RateLimitRule.Key;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qual regra vale para cada rota e quem é o sujeito (ADR-0001, D11), com o limitador falso. As regras são as mesmas
 * que os módulos declaram (ADR-0002, D5); o teste de integração confere as declarações reais.
 */
class RateLimitFilterTest {

    private static final Map<String, Limit> LIMITS = Map.of(
            "login", new Limit(10, Duration.ofMinutes(1)),
            "register", new Limit(10, Duration.ofHours(1)),
            "refresh", new Limit(30, Duration.ofMinutes(1)),
            "password-change", new Limit(5, Duration.ofMinutes(15)),
            "link-creation", new Limit(30, Duration.ofMinutes(1)),
            "redirect", new Limit(600, Duration.ofMinutes(1)));

    private static final List<RateLimitRule> RULES = List.of(
            RateLimitRule.exact("login", "POST", "/api/v1/auth/login", Key.IP),
            RateLimitRule.exact("register", "POST", "/api/v1/auth/register", Key.IP),
            RateLimitRule.exact("refresh", "POST", "/api/v1/auth/refresh", Key.IP),
            RateLimitRule.exact("password-change", "PUT", "/api/v1/users/me/password", Key.USER),
            RateLimitRule.exact("link-creation", "POST", "/api/v1/links", Key.USER),
            RateLimitRule.singleSegment("redirect", Set.of("GET", "HEAD"), Key.IP));

    private final RateLimiter limiter = mock(RateLimiter.class);
    private RateLimitFilter filter;

    @BeforeEach
    void allowEverything() {
        when(limiter.limit(anyString())).thenAnswer(invocation -> LIMITS.get(invocation.<String>getArgument(0)));
        when(limiter.tryAcquire(anyString(), any(), anyString())).thenReturn(new RateLimiter.Decision.Allowed());
        filter = new RateLimitFilter(limiter, RULES, new ProblemResponseWriter(JsonMapper.builder().build()));
    }

    @Test
    void eachRuleUsesTheConfiguredLimit() throws Exception {
        run("POST", "/api/v1/auth/register");

        verify(limiter).tryAcquire(eq("register"), eq(LIMITS.get("register")), anyString());
    }

    @Test
    void ruleWithoutConfiguredLimitFailsAtConstruction() {
        when(limiter.limit("sem-limite")).thenThrow(new IllegalStateException("Falta o limite"));
        List<RateLimitRule> rules = List.of(RateLimitRule.exact("sem-limite", "GET", "/x", Key.IP));
        ProblemResponseWriter writer = new ProblemResponseWriter(JsonMapper.builder().build());

        assertThatThrownBy(() -> new RateLimitFilter(limiter, rules, writer))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            POST, /api/v1/auth/login,    login
            POST, /api/v1/auth/register, register
            POST, /api/v1/auth/refresh,  refresh
            GET,  /aZ3kP9q,              redirect
            HEAD, /aZ3kP9q,              redirect
            GET,  /qualquer-coisa,       redirect
            """)
    void ipRulesUseTheClientAddress(String method, String path, String rule) throws Exception {
        run(method, path);

        verify(limiter).tryAcquire(eq(rule), any(), eq("203.0.113.10"));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            PUT,  /api/v1/users/me/password, password-change
            POST, /api/v1/links,             link-creation
            """)
    void userRulesUseTheAuthenticatedUser(String method, String path, String rule) throws Exception {
        MockHttpServletRequest request = request(method, path);
        request.setUserPrincipal(() -> "42");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).tryAcquire(eq(rule), any(), eq("42"));
    }

    @Test
    void clientRulesUseTheClientIdOfBasicAuthenticationOrOfTheForm() throws Exception {
        RateLimitRule rule = RateLimitRule.exact("token-client", "POST", "/oauth2/token", Key.CLIENT);
        when(limiter.limit("token-client")).thenReturn(new Limit(10, Duration.ofMinutes(1)));
        RateLimitFilter clientFilter = new RateLimitFilter(limiter, List.of(rule),
                new ProblemResponseWriter(JsonMapper.builder().build()));

        MockHttpServletRequest basic = request("POST", "/oauth2/token");
        basic.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder()
                .encodeToString("encurtador%2Dweb:segredo".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        clientFilter.doFilter(basic, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletRequest form = request("POST", "/oauth2/token");
        form.setParameter("client_id", "encurtador-cli");
        clientFilter.doFilter(form, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletRequest anonymous = request("POST", "/oauth2/token");
        anonymous.addHeader("Authorization", "Basic !!!");
        clientFilter.doFilter(anonymous, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).tryAcquire(eq("token-client"), any(), eq("encurtador-web"));
        verify(limiter).tryAcquire(eq("token-client"), any(), eq("encurtador-cli"));
        verify(limiter, org.mockito.Mockito.times(2)).tryAcquire(eq("token-client"), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            POST,   /api/v1/links
            PUT,    /api/v1/users/me/password
            """)
    void anonymousRequestOnAUserRuleIsLeftToTheAuthorization(String method, String path) throws Exception {
        MockFilterChain chain = run(method, path);

        verify(limiter, never()).tryAcquire(anyString(), any(), anyString());
        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            GET,    /livez
            GET,    /readyz
            GET,    /api/v1/links
            DELETE, /aZ3kP9q
            POST,   /aZ3kP9q
            GET,    /um/dois
            GET,    /
            POST,   /api/v1/auth/logout
            """)
    void otherRoutesAreNotLimited(String method, String path) throws Exception {
        MockFilterChain chain = run(method, path);

        verify(limiter, never()).tryAcquire(anyString(), any(), anyString());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectionAnswers429WithRetryAfterAndStopsTheChain() throws Exception {
        when(limiter.tryAcquire(eq("redirect"), any(), anyString()))
                .thenReturn(new RateLimiter.Decision.Rejected(Duration.ofMillis(12_300)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("GET", "/aZ3kP9q"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("13");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"", "\"status\":429",
                "\"title\":\"Muitas requisições\"", "\"instance\":\"/aZ3kP9q\"", "em 13 segundos");
    }

    private MockFilterChain run(String method, String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(method, path), new MockHttpServletResponse(), chain);
        return chain;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }
}
