package br.com.encurtador.bff.support;

import br.com.encurtador.bff.config.OAuthClientConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Sessão do BFF como ela fica depois do login (ADR-0004, D4): o usuário OIDC no contexto de segurança e os tokens no
 * repositório de clientes autorizados da sessão, o mesmo que o repasse usa.
 */
@TestComponent
public class TestSessions {

    public static final String SUBJECT = "assunto-1";
    public static final String ID_TOKEN = "id-token-de-teste";

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizedClientRepository authorizedClients;

    TestSessions(ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients) {
        this.registrations = registrations;
        this.authorizedClients = authorizedClients;
    }

    /** Access token válido por 15 minutos e refresh token. */
    public MockHttpSession loggedIn(String accessToken) {
        Instant now = Instant.now();
        return loggedIn(access(accessToken, now, now.plus(Duration.ofMinutes(15))), new OAuth2RefreshToken("refresh-1", now));
    }

    /** Access token vencido: o próximo repasse renova pelo refresh token. */
    public MockHttpSession withExpiredAccessToken() {
        Instant now = Instant.now();
        return loggedIn(access("vencido", now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(5))),
                new OAuth2RefreshToken("refresh-1", now.minus(Duration.ofMinutes(20))));
    }

    public MockHttpSession loggedIn(OAuth2AccessToken access, @Nullable OAuth2RefreshToken refresh) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue(ID_TOKEN)
                .subject(SUBJECT)
                .claim("email", "fulano@exemplo.com")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .build();
        var user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken, "sub");
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), OAuthClientConfig.REGISTRATION_ID);

        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        var request = new MockHttpServletRequest();
        request.setSession(session);
        var client = new OAuth2AuthorizedClient(registrations.findByRegistrationId(OAuthClientConfig.REGISTRATION_ID),
                SUBJECT, access, refresh);
        authorizedClients.saveAuthorizedClient(client, authentication, request, new MockHttpServletResponse());
        return session;
    }

    private static OAuth2AccessToken access(String value, Instant issuedAt, Instant expiresAt) {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, issuedAt, expiresAt,
                Set.of("openid", "email"));
    }
}
