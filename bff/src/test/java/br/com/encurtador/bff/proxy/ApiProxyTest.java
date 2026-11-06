package br.com.encurtador.bff.proxy;

import br.com.encurtador.bff.support.BffTest;
import br.com.encurtador.bff.support.FakeServer;
import br.com.encurtador.bff.support.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static br.com.encurtador.bff.support.SpaCsrf.spaCsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Repasse de {@code /api/**} ao app com o access token da sessão (ADR-0004, D5). */
@BffTest
class ApiProxyTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    FakeServer fakeApp;

    @Autowired
    FakeServer fakeAuthServer;

    @AfterEach
    void reset() {
        fakeApp.reset();
        fakeAuthServer.reset();
    }

    @Test
    void forwardsWithTheAccessTokenAndNothingFromTheBrowserButTheContentHeaders() throws Exception {
        fakeApp.reply("/api/v1/links", 200, "{\"content\":[],\"page\":0}");

        mvc.perform(get("/api/v1/links?page=0&size=20").session(sessions.loggedIn("token-1"))
                        .header("Authorization", "Basic bWFsOm1hbA==")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .header("Cookie", "outro=1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page").value(0));

        FakeServer.Received forwarded = fakeApp.last();
        assertThat(forwarded.method()).isEqualTo("GET");
        assertThat(forwarded.path()).isEqualTo("/api/v1/links");
        assertThat(forwarded.query()).isEqualTo("page=0&size=20");
        assertThat(forwarded.header("Authorization")).isEqualTo("Bearer token-1");
        assertThat(forwarded.header("Accept")).isEqualTo("application/json");
        assertThat(forwarded.headers()).doesNotContainKeys("X-Forwarded-For", "Cookie");
    }

    @Test
    void bodyLocationAndRetryAfterPassThrough() throws Exception {
        fakeApp.reply("/api/v1/links", 201, "application/json", "{\"code\":\"novo\"}",
                Map.of("Location", fakeApp.uri() + "/api/v1/links/novo", "Cache-Control", "no-store",
                        "X-Interno", "nao-passa"));

        mvc.perform(post("/api/v1/links").session(sessions.loggedIn("token-1")).with(spaCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://exemplo.com/\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/links/novo"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("X-Interno"))
                .andExpect(jsonPath("$.code").value("novo"));

        assertThat(fakeApp.last().body()).isEqualTo("{\"url\":\"https://exemplo.com/\"}");
        assertThat(fakeApp.last().header("Content-Type")).startsWith("application/json");
    }

    @Test
    void errorsOfTheAppPassUntranslated() throws Exception {
        fakeApp.reply("/api/v1/stats", 429, "application/problem+json", "{\"code\":\"RATE_LIMITED\"}",
                Map.of("Retry-After", "13"));

        mvc.perform(get("/api/v1/stats").session(sessions.loggedIn("token-1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "13"))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void deleteWithoutBodyIsForwarded() throws Exception {
        fakeApp.reply("/api/v1/links/velho", 204, "");

        mvc.perform(delete("/api/v1/links/velho").session(sessions.loggedIn("token-1")).with(spaCsrf()))
                .andExpect(status().isNoContent());

        assertThat(fakeApp.last().method()).isEqualTo("DELETE");
    }

    @Test
    void onlyTheRoutesOfThePanelAreForwarded() throws Exception {
        mvc.perform(get("/api/v1/qualquer").session(sessions.loggedIn("token-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(fakeApp.received()).isEmpty();
    }

    @Test
    void withoutSessionTheApiAnswers401() throws Exception {
        mvc.perform(get("/api/v1/links"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void mutatingWithoutCsrfIsRefused() throws Exception {
        mvc.perform(post("/api/v1/links").session(sessions.loggedIn("token-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        assertThat(fakeApp.received()).isEmpty();
    }

    @Test
    void slowAppAnswers503() throws Exception {
        fakeApp.replySlowly("/api/v1/stats", Duration.ofSeconds(2));

        mvc.perform(get("/api/v1/stats").session(sessions.loggedIn("token-1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void expiredAccessTokenIsRenewedWithTheRefreshTokenAndKeptInTheSession() throws Exception {
        fakeAuthServer.reply("/oauth2/token", 200, """
                {"access_token":"renovado","token_type":"Bearer","expires_in":900,"scope":"openid email"}""");
        fakeApp.reply("/api/v1/stats", 200, "{}");
        MockHttpSession session = sessions.withExpiredAccessToken();

        mvc.perform(get("/api/v1/stats").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/stats").session(session)).andExpect(status().isOk());

        assertThat(fakeAuthServer.received()).hasSize(1);
        assertThat(fakeAuthServer.last().body()).contains("grant_type=refresh_token", "refresh_token=refresh-1");
        assertThat(fakeApp.received()).extracting(received -> received.header("Authorization"))
                .containsExactly("Bearer renovado", "Bearer renovado");
    }

    @Test
    void refreshRefusedEndsTheSession() throws Exception {
        fakeAuthServer.reply("/oauth2/token", 400, "{\"error\":\"invalid_grant\"}");
        MockHttpSession session = sessions.withExpiredAccessToken();

        mvc.perform(get("/api/v1/stats").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        assertThat(session.isInvalid()).isTrue();
        assertThat(fakeApp.received()).isEmpty();
    }
}
