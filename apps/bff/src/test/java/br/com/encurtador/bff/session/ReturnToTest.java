package br.com.encurtador.bff.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

/** Validação do destino do login (ADR-0004, D4). */
class ReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {"/links", "/links/meu-artigo", "/estatisticas", "/conta", "/links?page=2"})
    void spaRoutesAreAccepted(String path) {
        assertThat(ReturnTo.sanitize(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"links", "//mal.example", "/\\mal.example", "https://mal.example/links", "/", "/admin",
            "/links/a/b", "/links?next=https://mal.example", "/bff/session"})
    void anythingElseGoesToTheLinks(String path) {
        assertThat(ReturnTo.sanitize(path)).isEqualTo(ReturnTo.DEFAULT);
    }

    @Test
    void afterTheLoginTheHandlerReturnsToTheSavedDestinationOnce() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(ReturnTo.SESSION_ATTRIBUTE, "/conta");
        var response = new MockHttpServletResponse();

        new ReturnToSuccessHandler().onAuthenticationSuccess(request, response, new TestingAuthenticationToken("u", "p"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/conta");
        assertThat(request.getSession().getAttribute(ReturnTo.SESSION_ATTRIBUTE)).isNull();
    }

    @Test
    void withoutASavedDestinationTheHandlerGoesToTheLinks() throws Exception {
        var response = new MockHttpServletResponse();

        new ReturnToSuccessHandler().onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken("u", "p"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/links");
    }
}
