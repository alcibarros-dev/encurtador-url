package br.com.encurtador.bff.session;

import br.com.encurtador.bff.config.BffProperties;
import br.com.encurtador.bff.config.OAuthClientConfig;
import br.com.encurtador.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Sessão do painel (ADR-0004, D4): quem está logado, cadastro, troca de senha e logout. Login e logout de verdade são
 * do provedor; o BFF só leva o navegador até ele e guarda os tokens.
 */
@RestController
class SessionController {

    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final TokenRevoker revoker;
    private final BffProperties properties;
    private final String panelUrl;
    private final CsrfLogoutHandler csrfLogout;
    private final CookieClearingLogoutHandler sessionCookie;

    SessionController(OAuth2AuthorizedClientRepository authorizedClients, TokenRevoker revoker,
                      BffProperties properties, AppProperties app, CsrfTokenRepository csrfTokens,
                      @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
        this.authorizedClients = authorizedClients;
        this.revoker = revoker;
        this.properties = properties;
        String base = app.baseUrl().toString();
        this.panelUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.csrfLogout = new CsrfLogoutHandler(csrfTokens);
        this.sessionCookie = new CookieClearingLogoutHandler(sessionCookieName);
    }

    /** E-mail do usuário, vindo do {@code id_token}. Sem sessão, o ponto de entrada responde 401. */
    @GetMapping("/bff/session")
    SessionResponse session(Authentication authentication) {
        return new SessionResponse(emailOf(authentication.getPrincipal()));
    }

    /**
     * Cadastro na página do provedor, com a volta para o início do login do painel: o auth-server deixa o usuário
     * logado ao fim do cadastro, e o login seguinte é silencioso.
     */
    @GetMapping("/bff/register")
    ResponseEntity<Void> register() {
        String continueTo = panelUrl + "/oauth2/authorization/" + OAuthClientConfig.REGISTRATION_ID
                + "?returnTo=" + ReturnTo.DEFAULT;
        return redirect(UriComponentsBuilder.fromUri(properties.registrationUrl())
                .queryParam("continue", continueTo)
                .encode()
                .build()
                .toUri());
    }

    /** Troca de senha na página do provedor, que volta para a página da conta. */
    @GetMapping("/bff/password")
    ResponseEntity<Void> changePassword() {
        return redirect(UriComponentsBuilder.fromUri(properties.passwordChangeUrl())
                .queryParam("continue", panelUrl + "/conta")
                .encode()
                .build()
                .toUri());
    }

    /**
     * Revoga o refresh token, encerra a sessão e devolve a URL de logout do provedor, para onde a SPA navega: sem essa
     * navegação, a sessão do auth-server sobreviveria, e o próximo "Entrar" seria silencioso.
     */
    @PostMapping("/bff/logout")
    LogoutResponse logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(OAuthClientConfig.REGISTRATION_ID,
                authentication, request);
        if (client != null && client.getRefreshToken() != null) {
            revoker.revokeRefreshToken(client.getRefreshToken().getTokenValue());
        }
        String idToken = authentication.getPrincipal() instanceof OidcUser oidc ? oidc.getIdToken().getTokenValue() : null;
        authorizedClients.removeAuthorizedClient(OAuthClientConfig.REGISTRATION_ID, authentication, request, response);
        csrfLogout.logout(request, response, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        sessionCookie.logout(request, response, authentication);
        return new LogoutResponse(logoutUrl(idToken));
    }

    private String logoutUrl(@Nullable String idToken) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(
                withoutTrailingSlash(properties.authPublicUrl().toString()) + "/connect/logout");
        if (idToken != null) {
            url.queryParam("id_token_hint", idToken);
        }
        return url.queryParam("post_logout_redirect_uri", panelUrl + "/")
                .queryParam("client_id", properties.clientId())
                .encode()
                .build()
                .toUriString();
    }

    private static @Nullable String emailOf(@Nullable Object principal) {
        if (principal instanceof OidcUser oidc) {
            return oidc.getEmail();
        }
        return principal instanceof OAuth2User user ? user.getAttribute("email") : null;
    }

    private static ResponseEntity<Void> redirect(URI location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    record SessionResponse(@Nullable String email) {
    }

    record LogoutResponse(String logoutUrl) {
    }
}
