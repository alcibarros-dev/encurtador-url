package br.com.encurtador.security.web;

import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import br.com.encurtador.shared.error.UnknownAccountException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 401 em RFC 9457. O entry point padrão do Resource Server só escreve o status e o {@code WWW-Authenticate}, sem
 * corpo: delegamos a ele o cabeçalho e escrevemos o ProblemDetail (ADR-0001, D9; corrige P5 e S4).
 */
final class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    static final String INVALID_TOKEN = UnknownAccountException.CODE;

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ProblemResponseWriter writer;

    ProblemDetailAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        bearer.commence(request, response, ex);
        HttpStatusCode status = HttpStatusCode.valueOf(response.getStatus());
        if (ex instanceof OAuth2AuthenticationException) {
            writer.write(request, response, ProblemDetails.of(status, INVALID_TOKEN,
                    "O token de acesso é inválido ou expirou."));
        } else {
            writer.write(request, response, ProblemDetails.of(status, UNAUTHENTICATED,
                    "Esta operação exige um token de acesso no cabeçalho Authorization."));
        }
    }
}
