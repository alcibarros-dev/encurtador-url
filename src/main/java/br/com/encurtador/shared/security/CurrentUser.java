package br.com.encurtador.shared.security;

import br.com.encurtador.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Extrai o id do usuário autenticado do access token. Controllers chamam isto e passam o id adiante; serviços nunca
 * leem o {@code SecurityContextHolder} (ADR-0001, D2 e D9).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** O validador do {@link JwtConfig} garante que o {@code sub} de todo token aceito é um id válido. */
    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    /** 401 para um token bem assinado cuja conta não existe mais: mesmo código de um token inválido. */
    public static ApiException accountNotFound() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ProblemDetailAuthenticationEntryPoint.INVALID_TOKEN,
                "A conta deste token não existe mais.");
    }

    static boolean isUserId(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
