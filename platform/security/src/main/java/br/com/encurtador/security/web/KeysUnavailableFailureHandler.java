package br.com.encurtador.security.web;

import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * Falha na validação do bearer token (ADR-0003, D6). Sem chave pública utilizável (cache vazio e JWKS fora), o
 * Spring Security lança {@link AuthenticationServiceException}, que o handler padrão relança como 500: aqui ela vira
 * 503 {@code AUTH_UNAVAILABLE} com {@code Retry-After}. Token inválido continua com o 401 do entry point.
 */
final class KeysUnavailableFailureHandler implements AuthenticationFailureHandler {

    static final String AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE";
    /** Segundos; a próxima busca do JWKS não depende disso, mas um cliente não deve insistir sem pausa. */
    static final String RETRY_AFTER_SECONDS = "10";

    private static final Logger log = LoggerFactory.getLogger(KeysUnavailableFailureHandler.class);

    private final AuthenticationEntryPoint entryPoint;
    private final ProblemResponseWriter writer;

    KeysUnavailableFailureHandler(AuthenticationEntryPoint entryPoint, ProblemResponseWriter writer) {
        this.entryPoint = entryPoint;
        this.writer = writer;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException ex) throws IOException, ServletException {
        if (ex instanceof AuthenticationServiceException) {
            log.warn("Token não validado: chaves públicas do emissor indisponíveis ({})", ex.getMessage());
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            writer.write(request, response, ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, AUTH_UNAVAILABLE,
                    "Não foi possível validar o token agora. Tente de novo em alguns segundos."));
            return;
        }
        entryPoint.commence(request, response, ex);
    }
}
