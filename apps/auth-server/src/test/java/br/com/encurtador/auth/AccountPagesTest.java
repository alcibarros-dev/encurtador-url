package br.com.encurtador.auth;

import br.com.encurtador.auth.support.AuthServerTest;
import br.com.encurtador.auth.support.OAuthFlows;
import br.com.encurtador.auth.support.OAuthFlows.Tokens;
import br.com.encurtador.ratelimit.RateLimitProperties;
import br.com.encurtador.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cadastro, login e troca de senha pelas páginas (ADR-0003, D9; ADR-0004, D4). */
@AuthServerTest
class AccountPagesTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OAuthFlows flows;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RateLimitProperties limits;

    @Test
    void registrationLogsTheUserInAndReturnsToThePanel() throws Exception {
        String email = "Nova-" + UUID.randomUUID() + "@Exemplo.com";
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/register").session(session).with(csrf()).param("email", "  " + email + " ")
                        .param("password", OAuthFlows.PASSWORD)
                        .param("continue", "http://localhost:8070/oauth2/authorization/encurtador"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:8070/oauth2/authorization/encurtador"));

        String normalized = email.toLowerCase();
        String subject = jdbc.sql("select subject from credentials where email = :email").param("email", normalized)
                .query(String.class).single();
        assertThat(UUID.fromString(subject)).isNotNull();
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
        mvc.perform(get("/").session(session)).andExpect(status().isOk()).andExpect(content().string(containsString(normalized)));

        // O login seguinte do painel é silencioso e emite o id_token, que exige o auth_time do login automático.
        OAuthFlows.Tokens tokens = flows.authorizationCode(session);
        assertThat(flows.decode(tokens.idToken()).getClaimAsInstant("auth_time")).isNotNull();
    }

    @Test
    void registrationNeverReturnsOutsideThePanel() throws Exception {
        mvc.perform(post("/register").with(csrf()).param("email", "x-" + UUID.randomUUID() + "@exemplo.com")
                        .param("password", OAuthFlows.PASSWORD).param("continue", "https://atacante.example/roubo"))
                .andExpect(redirectedUrl("http://localhost:8070/"));
        mvc.perform(get("/register").param("continue", "https://atacante.example/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("atacante"))));
    }

    @Test
    void registrationShowsEveryProblemInTheForm() throws Exception {
        String email = flows.newAccount();
        mvc.perform(post("/register").with(csrf()).param("email", email).param("password", OAuthFlows.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Já existe uma conta com este e-mail.")));
        mvc.perform(post("/register").with(csrf()).param("email", "sem-arroba").param("password", "curta"))
                .andExpect(content().string(containsString("Informe um e-mail válido.")))
                .andExpect(content().string(containsString("A senha precisa ter de 8 a 64 caracteres.")));
        mvc.perform(post("/register").with(csrf()).param("email", "emoji-" + UUID.randomUUID() + "@exemplo.com")
                        .param("password", "😀".repeat(20)))
                .andExpect(content().string(containsString("no máximo 72 bytes")));
    }

    @Test
    void wrongPasswordAndOversizedPasswordGetTheSameAnswer() throws Exception {
        String email = flows.newAccount();
        assertThat(flows.login(email, "outra-senha-qualquer").redirectedTo()).isEqualTo("/login?error");
        assertThat(flows.login(email, "á".repeat(40)).redirectedTo()).isEqualTo("/login?error");
        assertThat(flows.login("ninguem-" + UUID.randomUUID() + "@exemplo.com", OAuthFlows.PASSWORD).redirectedTo())
                .isEqualTo("/login?error");
    }

    @Test
    void loginNormalizesEmailAndPassword() throws Exception {
        String email = "acento-" + UUID.randomUUID() + "@exemplo.com";
        mvc.perform(post("/register").with(csrf()).param("email", email).param("password", "senha-é-composta"));

        // "e" + acento combinante é a mesma senha que "é" composto (NFC).
        assertThat(flows.login(email.toUpperCase(), "senha-é-composta").redirectedTo()).isEqualTo("/");
    }

    @Test
    void tooManyAttemptsForOneEmailBlockTheLoginBeforeTheBcrypt() throws Exception {
        String email = flows.newAccount();
        exhaust("login-email", email);

        String target = flows.login(email, OAuthFlows.PASSWORD).redirectedTo();

        assertThat(target).startsWith("/login?blocked=");
        mvc.perform(get(target)).andExpect(content().string(containsString("Muitas tentativas")));
    }

    @Test
    void passwordChangeRequiresTheCurrentPasswordAndEndsTheAuthorizations() throws Exception {
        String email = flows.newAccount();
        MockHttpSession session = flows.login(email, OAuthFlows.PASSWORD).session();
        Tokens tokens = flows.authorizationCode(session);

        mvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", "errada-errada").param("newPassword", "nova-senha-segura"))
                .andExpect(content().string(containsString("A senha atual não confere.")));
        mvc.perform(post("/account/password").session(session).with(csrf())
                        .param("currentPassword", OAuthFlows.PASSWORD).param("newPassword", "nova-senha-segura")
                        .param("continue", "http://localhost:8070/conta"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http://localhost:8070/conta")));

        MvcResult refresh = flows.refresh(tokens.refreshToken());
        assertThat(refresh.getResponse().getStatus()).isEqualTo(400);
        assertThat(flows.login(email, OAuthFlows.PASSWORD).redirectedTo()).isEqualTo("/login?error");
        assertThat(flows.login(email, "nova-senha-segura").redirectedTo()).isEqualTo("/");
    }

    @Test
    void pagesRequireLoginAndRenderErrorsAsHtml() throws Exception {
        mvc.perform(get("/account/password")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Entrar")));
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        mvc.perform(get("/account/password").session(session)).andExpect(status().isOk());
        mvc.perform(get("/nada-aqui").session(session)).andExpect(status().isNotFound());
    }

    /** Preenche o contador da regra até o limite, como se as tentativas já tivessem acontecido. */
    private void exhaust(String rule, String subject) {
        RateLimitProperties.Limit limit = limits.rateLimit().get(rule);
        String key = RateLimiter.key(rule, subject, limit.window(), Instant.now());
        redis.opsForValue().set(key, Long.toString(limit.requests()), Duration.ofMinutes(5));
    }
}
