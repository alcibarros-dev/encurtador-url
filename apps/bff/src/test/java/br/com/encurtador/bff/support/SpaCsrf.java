package br.com.encurtador.bff.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * CSRF como a SPA faz (ADR-0004, D4): o valor do cookie {@code XSRF-TOKEN} volta no cabeçalho {@code X-XSRF-TOKEN}.
 * No lugar do {@code csrf()} do spring-security-test, que troca o repositório do filtro para o resto do contexto.
 */
public final class SpaCsrf {

    private SpaCsrf() {
    }

    public static RequestPostProcessor spaCsrf() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "token-de-csrf-da-spa"));
            request.addHeader("X-XSRF-TOKEN", "token-de-csrf-da-spa");
            return request;
        };
    }

    /** Cookie presente, cabeçalho com outro valor: o que um site de terceiros conseguiria enviar. */
    public static RequestPostProcessor wrongCsrf() {
        return request -> {
            request.setCookies(new Cookie("XSRF-TOKEN", "token-de-csrf-da-spa"));
            request.addHeader("X-XSRF-TOKEN", "outro-valor");
            return request;
        };
    }
}
