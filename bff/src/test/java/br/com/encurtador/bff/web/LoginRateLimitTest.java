package br.com.encurtador.bff.web;

import br.com.encurtador.bff.support.BffTest;
import br.com.encurtador.ratelimit.RateLimitProperties;
import br.com.encurtador.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Limite do início do login por IP (ADR-0004, D4): cada início cria uma sessão anônima. */
@BffTest
class LoginRateLimitTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RateLimitProperties limits;

    @Autowired
    Clock clock;

    private String key;

    @AfterEach
    void cleanUp() {
        redis.delete(key);
    }

    @Test
    void aboveTheLimitTheLoginStartAndTheRegistrationGet429() throws Exception {
        var limit = limits.rateLimit().get("login-start");
        key = RateLimiter.key("login-start", "127.0.0.1", limit.window(), clock.instant());
        redis.opsForValue().set(key, Long.toString(limit.requests()), limit.window().plusMinutes(1));

        mvc.perform(get("/oauth2/authorization/encurtador"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(get("/bff/register")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/")).andExpect(status().isOk());
    }
}
