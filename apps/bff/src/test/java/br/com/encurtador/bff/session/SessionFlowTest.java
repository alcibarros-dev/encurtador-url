package br.com.encurtador.bff.session;

import br.com.encurtador.bff.support.BffTest;
import br.com.encurtador.bff.support.FakeServer;
import br.com.encurtador.bff.support.SpaCsrf;
import br.com.encurtador.bff.support.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static br.com.encurtador.bff.support.SpaCsrf.spaCsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, sessão, cadastro, troca de senha e logout do painel (ADR-0004, D4). */
@BffTest
class SessionFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    FakeServer fakeAuthServer;

    @AfterEach
    void reset() {
        fakeAuthServer.reset();
    }

    @Test
    void withoutSessionTheSessionEndpointAnswers401ProblemNotARedirect() throws Exception {
        mvc.perform(get("/bff/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void sessionShowsTheEmailOfTheIdTokenAndIssuesTheCsrfCookie() throws Exception {
        mvc.perform(get("/bff/session").session(sessions.loggedIn("token-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fulano@exemplo.com"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void loginStartGoesToThePublicAuthorizeEndpointWithPkceAndKeepsTheReturnTo() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/encurtador").param("returnTo", "/estatisticas"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://auth.test/oauth2/authorize?")))
                .andReturn();
        UriComponents authorize = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build();

        assertThat(authorize.getQueryParams().getFirst("client_id")).isEqualTo("encurtador-web");
        assertThat(authorize.getQueryParams().getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(authorize.getQueryParams().getFirst("code_challenge")).isNotBlank();
        assertThat(authorize.getQueryParams().getFirst("nonce")).isNotBlank();
        assertThat(decode(authorize.getQueryParams().getFirst("scope"))).isEqualTo("openid email");
        assertThat(decode(authorize.getQueryParams().getFirst("redirect_uri")))
                .isEqualTo("http://localhost/login/oauth2/code/encurtador");
        assertThat(result.getRequest().getSession().getAttribute(ReturnTo.SESSION_ATTRIBUTE)).isEqualTo("/estatisticas");
    }

    @Test
    void returnToOutsideTheSpaFallsBackToTheLinks() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/authorization/encurtador").param("returnTo", "//mal.example"))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getRequest().getSession().getAttribute(ReturnTo.SESSION_ATTRIBUTE)).isEqualTo("/links");
    }

    @Test
    void registerGoesToTheProviderWithTheLoginStartOfThePanelAsTheWayBack() throws Exception {
        MvcResult result = mvc.perform(get("/bff/register")).andExpect(status().isFound()).andReturn();
        UriComponents register = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build();

        assertThat(register.getPath()).isEqualTo("/register");
        assertThat(register.getHost()).isEqualTo("auth.test");
        assertThat(decode(register.getQueryParams().getFirst("continue")))
                .isEqualTo("http://painel.test/oauth2/authorization/encurtador?returnTo=/links");
    }

    @Test
    void passwordChangeGoesToTheProviderAndComesBackToTheAccountPage() throws Exception {
        MvcResult result = mvc.perform(get("/bff/password")).andExpect(status().isFound()).andReturn();
        UriComponents password = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl()).build();

        assertThat(password.getPath()).isEqualTo("/account/password");
        assertThat(decode(password.getQueryParams().getFirst("continue"))).isEqualTo("http://painel.test/conta");
    }

    @Test
    void logoutRequiresTheCsrfTokenOfTheCookieInTheHeader() throws Exception {
        mvc.perform(post("/bff/logout").session(sessions.loggedIn("token-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/bff/logout").session(sessions.loggedIn("token-1")).with(SpaCsrf.wrongCsrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesTheRefreshTokenEndsTheSessionAndPointsToTheProviderLogout() throws Exception {
        fakeAuthServer.reply("/oauth2/revoke", 200, "");
        MockHttpSession session = sessions.loggedIn("token-1");

        MvcResult result = mvc.perform(post("/bff/logout").session(session).with(spaCsrf()))
                .andExpect(status().isOk())
                .andReturn();

        FakeServer.Received revoke = fakeAuthServer.last();
        assertThat(revoke.path()).isEqualTo("/oauth2/revoke");
        assertThat(revoke.body()).contains("token=refresh-1", "token_type_hint=refresh_token");
        assertThat(revoke.header("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString("encurtador-web:test-web-secret".getBytes(StandardCharsets.UTF_8)));
        String logoutUrl = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.logoutUrl");
        UriComponents logout = UriComponentsBuilder.fromUriString(logoutUrl).build();
        assertThat(logout.getHost()).isEqualTo("auth.test");
        assertThat(logout.getPath()).isEqualTo("/connect/logout");
        assertThat(logout.getQueryParams().getFirst("id_token_hint")).isEqualTo(TestSessions.ID_TOKEN);
        assertThat(decode(logout.getQueryParams().getFirst("post_logout_redirect_uri"))).isEqualTo("http://painel.test/");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logoutSucceedsEvenIfTheRevocationFails() throws Exception {
        fakeAuthServer.reply("/oauth2/revoke", 503, "{}");

        mvc.perform(post("/bff/logout").session(sessions.loggedIn("token-1")).with(spaCsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoutUrl").value(startsWith("http://auth.test/connect/logout?")));
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
