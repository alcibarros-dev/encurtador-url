package br.com.encurtador.security.web;

import br.com.encurtador.security.CurrentUser;
import br.com.encurtador.security.UnknownSubjectException;
import br.com.encurtador.security.UserIdResolver;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolve {@code @CurrentUser long userId} a partir do token validado, pelo {@link UserIdResolver} do provedor de
 * identidade (ADR-0002, D3). As exceções lançadas aqui são de autenticação: o Spring Security as transforma em 401.
 */
final class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserIdResolver users;

    CurrentUserArgumentResolver(UserIdResolver users) {
        this.users = users;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return parameter.hasParameterAnnotation(CurrentUser.class) && (type == long.class || type == Long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AuthenticationCredentialsNotFoundException("Rota com @CurrentUser sem token de acesso.");
        }
        try {
            return users.resolve(token.getName(), token.getTokenAttributes());
        } catch (UnknownSubjectException ex) {
            throw new InvalidBearerTokenException(ex.getMessage(), ex);
        }
    }
}
