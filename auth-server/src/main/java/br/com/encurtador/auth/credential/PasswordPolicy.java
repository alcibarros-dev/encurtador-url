package br.com.encurtador.auth.credential;

import br.com.encurtador.shared.validation.MaxUtf8BytesValidator;

import java.text.Normalizer;

/**
 * Política de senha (ADR-0001, D9): normalizada para NFC, de 8 a 64 caracteres e no máximo 72 bytes em UTF-8.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /** Limite do bcrypt: o Spring Security lança exceção ao codificar mais que isso (CVE-2025-22228). */
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * NFC no cadastro, no login e na troca: "é" composto e "e" + acento combinante viram a mesma senha.
     * {@code null} continua {@code null}.
     */
    public static String normalize(String raw) {
        return raw == null ? null : Normalizer.normalize(raw, Normalizer.Form.NFC);
    }

    /** Se a senha pode ser entregue ao encoder sem que ele lance exceção. */
    public static boolean fitsEncoder(String password) {
        return MaxUtf8BytesValidator.utf8Length(password) <= MAX_UTF8_BYTES;
    }
}
