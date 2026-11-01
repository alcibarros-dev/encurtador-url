package br.com.encurtador.identity.domain;

import java.util.Locale;

/**
 * Regras do e-mail de login (ADR-0001, D9): sem espaços nas pontas, em minúsculas e com até 254 caracteres.
 */
public final class EmailAddress {

    public static final int MAX_LENGTH = 254;

    private EmailAddress() {
    }

    /** {@code null} continua {@code null}, para que a validação acuse o campo ausente. */
    public static String normalize(String raw) {
        return raw == null ? null : raw.strip().toLowerCase(Locale.ROOT);
    }
}
