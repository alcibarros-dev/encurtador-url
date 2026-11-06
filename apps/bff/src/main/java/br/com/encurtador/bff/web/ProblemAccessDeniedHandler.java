package br.com.encurtador.bff.web;

import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** 403 em problem+json; na prática, token de CSRF ausente ou inválido (ADR-0004, D4). */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter writer;

    public ProblemAccessDeniedHandler(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writer.write(request, response, ProblemDetails.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "A requisição foi recusada. Recarregue a página e tente de novo."));
    }
}
