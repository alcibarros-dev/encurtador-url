package br.com.encurtador.security.jwt;

import br.com.encurtador.security.CurrentUser;
import br.com.encurtador.security.UserIdResolver;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadeia de segurança completa sem nenhuma chave utilizável (cache vazio e JWKS fora): 503 {@code AUTH_UNAVAILABLE}
 * com {@code Retry-After}, e não o 500 padrão do Spring Security (ADR-0003, D6 e R2). Token malformado e ausência de
 * token continuam com 401, porque falham antes de qualquer busca de chave.
 */
@SpringBootTest(properties = {
        "app.base-url=http://localhost:8080",
        "app.jwt.issuer=" + KeysUnavailableTest.ISSUER,
        "app.jwt.audience=encurtador-api",
        "app.jwt.clock-skew=5s",
        "app.redis-guard.failure-threshold=5",
        "app.redis-guard.open-duration=5s"})
@AutoConfigureMockMvc
@Import(KeysUnavailableTest.ProbeController.class)
class KeysUnavailableTest {

    static final String ISSUER = "http://auth.test";
    private static final RSAKey KEY = JwksStub.rsaKey("chave-1");
    private static final JwksStub JWKS = new JwksStub(KEY);

    static {
        JWKS.available(false);
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserIdResolver users;

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.jwk-set-uri", () -> JWKS.uri().toString());
    }

    @AfterAll
    static void stop() {
        JWKS.close();
    }

    @Test
    void wellFormedTokenWithoutAnyKeyAvailableGets503() throws Exception {
        String token = JwksStub.sign(KEY, null, new JWTClaimsSet.Builder()
                .subject("assunto-1")
                .issuer(ISSUER)
                .audience("encurtador-api")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(15))))
                .build());

        // A terceira tentativa já esbarra no limite de buscas do JWKS e continua sendo indisponibilidade.
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "10"))
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.instance").value("/api/v1/eu"));
        }
    }

    @Test
    void malformedTokenIsStillAnInvalidToken() throws Exception {
        mvc.perform(get("/api/v1/eu").header("Authorization", "Bearer nao.e.um-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void missingTokenIsStillUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/eu"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/eu")
        long me(@CurrentUser long userId) {
            return userId;
        }
    }
}
