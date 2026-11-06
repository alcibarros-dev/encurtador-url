package br.com.encurtador.auth.support;

import br.com.encurtador.auth.credential.RegistrationService;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Os fluxos do protocolo percorridos pelo MockMvc, como um navegador e um cliente fariam. */
@TestComponent
public class OAuthFlows {

    public static final String WEB_CLIENT = "encurtador-web";
    public static final String WEB_SECRET = "test-web-secret";
    public static final String WEB_REDIRECT = "http://localhost:8070/login/oauth2/code/encurtador";
    public static final String PASSWORD = "senha-segura-1";

    private static final SecureRandom random = new SecureRandom();

    private final MockMvc mvc;
    private final RegistrationService registrations;
    private final NimbusJwtDecoder decoder;
    private final JsonMapper json = JsonMapper.builder().build();

    public OAuthFlows(MockMvc mvc, RegistrationService registrations, JWKSource<SecurityContext> keys) {
        this.mvc = mvc;
        this.registrations = registrations;
        this.decoder = NimbusJwtDecoder.withJwkSource(keys).build();
    }

    /** Uma conta nova, com e-mail único. */
    public String newAccount() {
        String email = "usuario-" + UUID.randomUUID() + "@exemplo.com";
        assertThat(registrations.register(email, PASSWORD)).isPresent();
        return email;
    }

    /** Login pelo formulário; devolve a sessão autenticada e o endereço para onde o login mandou. */
    public Login login(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", email).param("password", password))
                .andReturn();
        return new Login(session, result.getResponse().getRedirectedUrl());
    }

    /** Código de autorização com PKCE e troca pelo token, para o cliente do painel. */
    public Tokens authorizationCode(MockHttpSession session) throws Exception {
        Pkce pkce = Pkce.create();
        String location = authorize(session, WEB_CLIENT, WEB_REDIRECT, "openid email", pkce);
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        assertThat(code).as("código na volta de %s", location).isNotBlank();
        MvcResult result = mvc.perform(post("/oauth2/token").with(httpBasic(WEB_CLIENT, WEB_SECRET))
                        .param("grant_type", "authorization_code").param("code", code)
                        .param("redirect_uri", WEB_REDIRECT).param("code_verifier", pkce.verifier()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return tokens(result);
    }

    /** {@code /oauth2/authorize} com uma sessão já autenticada; devolve o redirecionamento para o cliente. */
    public String authorize(MockHttpSession session, String clientId, String redirectUri, String scope, Pkce pkce)
            throws Exception {
        MvcResult result = mvc.perform(get(authorizeUri(clientId, redirectUri, scope, pkce)).session(session)
                        .accept(MediaType.TEXT_HTML))
                .andReturn();
        return result.getResponse().getRedirectedUrl();
    }

    /** O SAS lê os parâmetros do {@code /oauth2/authorize} da query string, como um navegador os mandaria. */
    public static java.net.URI authorizeUri(String clientId, String redirectUri, String scope, Pkce pkce) {
        return UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("scope", scope).queryParam("state", "estado")
                .queryParam("code_challenge", pkce.challenge()).queryParam("code_challenge_method", "S256")
                .encode().build().toUri();
    }

    public MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/oauth2/token").with(httpBasic(WEB_CLIENT, WEB_SECRET))
                        .param("grant_type", "refresh_token").param("refresh_token", refreshToken))
                .andReturn();
    }

    public Tokens tokens(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return new Tokens(text(body, "access_token"), text(body, "refresh_token"), text(body, "id_token"));
    }

    public JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    public record Login(MockHttpSession session, String redirectedTo) {
    }

    public record Tokens(String accessToken, String refreshToken, String idToken) {
    }

    public record Pkce(String verifier, String challenge) {

        public static Pkce create() {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
