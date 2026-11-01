package br.com.encurtador.shared.security;

import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 403 em RFC 9457 para usuário autenticado sem acesso à rota, com o cabeçalho do handler padrão do Resource Server
 * (ADR-0001, D9).
 */
final class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    static final String ACCESS_DENIED = "ACCESS_DENIED";

    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
    private final ProblemResponseWriter writer;

    ProblemDetailAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        bearer.handle(request, response, ex);
        writer.write(request, response, ProblemDetails.of(HttpStatusCode.valueOf(response.getStatus()), ACCESS_DENIED,
                "Você não tem permissão para acessar este recurso."));
    }
}
