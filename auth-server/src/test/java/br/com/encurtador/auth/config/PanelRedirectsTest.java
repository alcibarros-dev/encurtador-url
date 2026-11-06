package br.com.encurtador.auth.config;

import br.com.encurtador.auth.config.AuthServerProperties.SigningKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Só voltas para as origens do painel; o resto cai no endereço padrão (ADR-0004, D4). */
class PanelRedirectsTest {

    private final PanelRedirects redirects = new PanelRedirects(new AuthServerProperties("encurtador-api",
            new SigningKey("k", null), null,
            List.of(URI.create("http://localhost:8070"), URI.create("https://painel.encurta.dor")),
            URI.create("https://painel.encurta.dor/")));

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8070/conta", "https://painel.encurta.dor/links?x=1",
            "https://painel.encurta.dor:443/", "HTTP://LOCALHOST:8070/"})
    void acceptsThePanelOrigins(String target) {
        assertThat(redirects.target(target)).isEqualTo(target);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://atacante.example/", "http://localhost:8071/", "https://painel.encurta.dor.evil/",
            "/conta", "//painel.encurta.dor/", "https://user@painel.encurta.dor/", "javascript:alert(1)", "http://[::1"})
    void anythingElseGoesToTheDefault(String target) {
        assertThat(redirects.target(target)).isEqualTo("https://painel.encurta.dor/");
    }
}
