package br.com.encurtador.bff.web;

import br.com.encurtador.shared.error.ProblemDetails;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Sem sessão válida, {@code /api/**} e {@code /bff/**} respondem 401 em problem+json, nunca um redirecionamento para o
 * login (ADR-0004, D4): a SPA mostra o aviso e o botão "Entrar".
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String SESSION_REQUIRED = "UNAUTHENTICATED";

    private final ProblemResponseWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        writer.write(request, response, ProblemDetails.of(HttpStatus.UNAUTHORIZED, SESSION_REQUIRED,
                "Entre no painel para continuar."));
    }
}
