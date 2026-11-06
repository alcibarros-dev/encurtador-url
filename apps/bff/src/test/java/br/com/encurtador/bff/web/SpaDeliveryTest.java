package br.com.encurtador.bff.web;

import br.com.encurtador.bff.support.BffTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Entrega da SPA com a CSP e os cabeçalhos de D9 e as rotas de D6 (ADR-0004, D12). */
@BffTest
class SpaDeliveryTest {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Autowired
    MockMvc mvc;

    @Test
    void indexComesWithTheSecurityHeadersAndWithoutCache() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<app-root>")))
                .andExpect(header().string("Content-Security-Policy", CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/links", "/links/meu-artigo", "/estatisticas", "/conta"})
    void spaRoutesServeTheIndex(String route) throws Exception {
        mvc.perform(get(route))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<app-root>")));
    }

    @Test
    void unknownAddressGivesTheBrowserThe404OfTheSpaAndTheSpaAProblem() throws Exception {
        mvc.perform(get("/nao/existe").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("<app-root>")));
        mvc.perform(get("/nao/existe").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void hashedFilesAreCachedForAYear() throws Exception {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:META-INF/resources/main-*.js");
        assertThat(scripts).isNotEmpty();

        mvc.perform(get("/" + scripts[0].getFilename()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
        mvc.perform(get("/index.html"))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void livenessIsPublic() throws Exception {
        mvc.perform(get("/livez")).andExpect(status().isOk());
    }
}
