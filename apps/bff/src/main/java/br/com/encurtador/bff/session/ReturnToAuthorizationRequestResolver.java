package br.com.encurtador.bff.session;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Início do login com {@code ?returnTo=} (ADR-0004, D4): o Spring Security não tem esse parâmetro. O destino é
 * validado e guardado na sessão; o {@link ReturnToSuccessHandler} volta para ele. O PKCE vem do resolvedor padrão,
 * porque o registro exige prova de posse.
 */
public final class ReturnToAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public ReturnToAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return remember(request, delegate.resolve(request));
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return remember(request, delegate.resolve(request, clientRegistrationId));
    }

    private static @Nullable OAuth2AuthorizationRequest remember(HttpServletRequest request,
                                                                 @Nullable OAuth2AuthorizationRequest authorization) {
        if (authorization != null) {
            request.getSession().setAttribute(ReturnTo.SESSION_ATTRIBUTE,
                    ReturnTo.sanitize(request.getParameter("returnTo")));
        }
        return authorization;
    }
}
