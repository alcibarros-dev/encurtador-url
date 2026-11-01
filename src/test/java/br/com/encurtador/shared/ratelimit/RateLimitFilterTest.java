package br.com.encurtador.shared.ratelimit;

import br.com.encurtador.shared.error.ProblemResponseWriter;
import br.com.encurtador.shared.ratelimit.RateLimitProperties.Limit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Qual regra vale para cada rota e quem é o sujeito (ADR-0001, D11), com o limitador falso. */
class RateLimitFilterTest {

    private static final Limit LOGIN = new Limit(10, Duration.ofMinutes(1));
    private static final Limit LOGIN_EMAIL = new Limit(5, Duration.ofMinutes(15));
    private static final Limit REGISTER = new Limit(10, Duration.ofHours(1));
    private static final Limit REFRESH = new Limit(30, Duration.ofMinutes(1));
    private static final Limit PASSWORD = new Limit(5, Duration.ofMinutes(15));
    private static final Limit LINKS = new Limit(30, Duration.ofMinutes(1));
    private static final Limit REDIRECT = new Limit(600, Duration.ofMinutes(1));

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final RateLimitFilter filter = new RateLimitFilter(limiter,
            new RateLimitProperties(LOGIN, LOGIN_EMAIL, REGISTER, REFRESH, PASSWORD, LINKS, REDIRECT),
            new ProblemResponseWriter(JsonMapper.builder().build()));

    @BeforeEach
    void allowEverything() {
        when(limiter.tryAcquire(anyString(), any(), anyString())).thenReturn(new RateLimiter.Decision.Allowed());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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
    void userRulesUseTheTokenSubject(String method, String path, String rule) throws Exception {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        run(method, path);

        verify(limiter).tryAcquire(eq(rule), any(), eq("42"));
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
