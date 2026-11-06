package br.com.encurtador.auth.token;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Autenticação de cliente público no fluxo de dispositivo (ADR-0003, D3). O conversor padrão do SAS para cliente
 * público só aceita a requisição que traz {@code code_verifier}; o {@code encurtador-cli} só manda o
 * {@code client_id}, em {@code /oauth2/device_authorization} e na troca do {@code device_code}.
 */
public final class DeviceClientAuthenticationConverter implements AuthenticationConverter {

    private final String deviceAuthorizationUri;

    public DeviceClientAuthenticationConverter(String deviceAuthorizationUri) {
        this.deviceAuthorizationUri = deviceAuthorizationUri;
    }

    @Override
    public @Nullable Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || !(isDeviceAuthorization(request) || isDeviceCodeExchange(request))) {
            return null;
        }
        String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null, null);
    }

    private boolean isDeviceAuthorization(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).equals(deviceAuthorizationUri);
    }

    private static boolean isDeviceCodeExchange(HttpServletRequest request) {
        return AuthorizationGrantType.DEVICE_CODE.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                && request.getParameter(OAuth2ParameterNames.DEVICE_CODE) != null;
    }
}
