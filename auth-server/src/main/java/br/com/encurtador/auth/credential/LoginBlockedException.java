package br.com.encurtador.auth.credential;

import org.springframework.security.core.AuthenticationException;

import java.time.Duration;

/** Tentativas de login acima do limite por IP ou por e-mail (ADR-0003, D9); a página de login mostra a espera. */
public class LoginBlockedException extends AuthenticationException {

    private final long retryAfterSeconds;

    public LoginBlockedException(Duration retryAfter) {
        super("Muitas tentativas de login");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
