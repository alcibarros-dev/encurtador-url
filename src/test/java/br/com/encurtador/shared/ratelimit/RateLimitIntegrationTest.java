package br.com.encurtador.shared.ratelimit;

import br.com.encurtador.identity.application.RegistrationService;
import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.shared.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.shared.security.AccessTokenIssuer;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedisProxy;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Limites pela cadeia HTTP real (ADR-0001, D11; cenário 16 do D14). Os testes compartilham o IP 127.0.0.1, então o
 * perfil de teste usa limites altíssimos; aqui o contador da janela é preenchido até o limite configurado, e a
 * requisição seguinte é a primeira acima dele.
 */
@IntegrationTest
class RateLimitIntegrationTest {

    private static final String CLIENT_IP = "127.0.0.1";
    private static final String PASSWORD = "senha-segura-1";

    @LocalServerPort
    int port;

    @Autowired
    RateLimitProperties limits;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MutableClock clock;

    @Autowired
    RegistrationService registration;

    @Autowired
    AccessTokenIssuer tokenIssuer;

    @Autowired
    LinkService links;

    @Autowired
    RedisProxy redisProxy;

    @Autowired
    RedisGuard guard;

    @Autowired
    MeterRegistry meters;

    private ApiClient api;
    private final List<String> exhaustedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    /** O contador do IP de teste é compartilhado: um limite esgotado aqui barraria as classes seguintes. */
    @AfterEach
    void cleanUp() {
        redis.delete(exhaustedKeys);
        clock.reset();
    }

    @Test
    void redirectAboveTheLimitGets429WithRetryAfterUntilTheWindowTurns() {
        startOfWindow(limits.redirect());
        String code = links.create(newUser(), "https://exemplo.com/", null, null).code();
        double rejected = meters.counter("shortener.ratelimit.rejected", "rule", "redirect").count();
        exhaust("redirect", limits.redirect(), CLIENT_IP);

        Response limited = api.get("/" + code, null);

        assertRateLimited(limited, limits.redirect());
        assertThat(meters.counter("shortener.ratelimit.rejected", "rule", "redirect").count()).isEqualTo(rejected + 1);
        clock.advance(limits.redirect().window());
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);
    }

    @Test
    void loginIsLimitedPerIp() {
        startOfWindow(limits.login());
        exhaust("login", limits.login(), CLIENT_IP);

        assertRateLimited(login(newEmail()), limits.login());
    }

    /** Força bruta distribuída por vários IPs contra uma conta só. */
    @Test
    void loginIsLimitedPerEmailEvenWithTheRightPassword() {
        startOfWindow(limits.loginEmail());
        String email = newEmail();
        registration.register(email, PASSWORD);
        exhaust("login-email", limits.loginEmail(), email);

        assertRateLimited(login(email), limits.loginEmail());
        assertThat(login(newEmailOfANewUser()).status()).isEqualTo(200);
    }

    @Test
    void registerIsLimitedPerIp() {
        startOfWindow(limits.register());
        exhaust("register", limits.register(), CLIENT_IP);

        assertRateLimited(api.post("/api/v1/auth/register", Map.of("email", newEmail(), "password", PASSWORD)),
                limits.register());
    }

    @Test
    void refreshIsLimitedPerIp() {
        startOfWindow(limits.refresh());
        exhaust("refresh", limits.refresh(), CLIENT_IP);

        assertRateLimited(api.post("/api/v1/auth/refresh", Map.of("refreshToken", "qualquer")), limits.refresh());
    }

    @Test
    void passwordChangeIsLimitedPerUser() {
        startOfWindow(limits.passwordChange());
        long userId = newUser();
        exhaust("password-change", limits.passwordChange(), Long.toString(userId));

        Response response = api.put("/api/v1/users/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "nova-senha-segura"),
                tokenIssuer.issue(userId).value());

        assertRateLimited(response, limits.passwordChange());
    }

    @Test
    void linkCreationIsLimitedPerUserAndOtherUsersAreUnaffected() {
        startOfWindow(limits.linkCreation());
        long userId = newUser();
        exhaust("link-creation", limits.linkCreation(), Long.toString(userId));

        Response limited = api.post("/api/v1/links", Map.of("url", "https://exemplo.com/"),
                tokenIssuer.issue(userId).value());
        Response other = api.post("/api/v1/links", Map.of("url", "https://exemplo.com/"),
                tokenIssuer.issue(newUser()).value());

        assertRateLimited(limited, limits.linkCreation());
        assertThat(other.status()).isEqualTo(201);
    }

    /** Cenário 16, parte do Redis fora: o limitador libera, e a requisição segue normalmente. */
    @Test
    void redisDownLetsRequestsThroughAndIsCounted() {
        String code = links.create(newUser(), "https://exemplo.com/", null, null).code();
        double failOpen = meters.counter("shortener.ratelimit.failopen", "rule", "redirect").count();
        redisProxy.cut();
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(api.get("/" + code, null).status()).isEqualTo(302);
            }
            assertThat(meters.counter("shortener.ratelimit.failopen", "rule", "redirect").count())
                    .isEqualTo(failOpen + 5);
        } finally {
            redisProxy.restore();
        }
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
            api.get("/" + code, null);
            return guard.state() == RedisGuard.State.CLOSED;
        });
    }

    /** Leva o contador da janela atual ao limite configurado. */
    private void exhaust(String rule, Limit limit, String subject) {
        String key = RateLimiter.key(rule, subject, limit.window(), clock.instant());
        redis.opsForValue().set(key, Long.toString(limit.requests()), limit.window().plusMinutes(1));
        exhaustedKeys.add(key);
    }

    /**
     * Põe o relógio da aplicação no começo de uma janela, para que preencher o contador e fazer a requisição caiam
     * na mesma janela. Tokens são emitidos depois disso, para não expirarem com o avanço.
     */
    private void startOfWindow(Limit limit) {
        long windowMillis = limit.window().toMillis();
        clock.advance(Duration.ofMillis(windowMillis - clock.millis() % windowMillis + 100));
    }

    private static void assertRateLimited(Response response, Limit limit) {
        assertThat(response.status()).isEqualTo(429);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo("RATE_LIMITED");
        assertThat(response.header("Retry-After")).hasValueSatisfying(value ->
                assertThat(Long.parseLong(value)).isBetween(1L, limit.window().toSeconds()));
    }

    private Response login(String email) {
        return api.post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
    }

    private long newUser() {
        return registration.register(newEmail(), PASSWORD).id();
    }

    private String newEmailOfANewUser() {
        String email = newEmail();
        registration.register(email, PASSWORD);
        return email;
    }

    private static String newEmail() {
        return "limite-" + UUID.randomUUID() + "@exemplo.com";
    }
}
