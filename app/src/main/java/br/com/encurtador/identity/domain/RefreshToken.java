package br.com.encurtador.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh token opaco: 256 bits aleatórios em base64url. O banco guarda só o SHA-256 (ADR-0001, D9).
 */
public record RefreshToken(String value) {

    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static RefreshToken generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return new RefreshToken(ENCODER.encodeToString(bytes));
    }

    public String hash() {
        return hashOf(value);
    }

    /** SHA-256 em hexadecimal minúsculo (64 caracteres), o formato da coluna {@code token_hash}. */
    public static String hashOf(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 é obrigatório em toda JVM", ex);
        }
    }

    @Override
    public String toString() {
        return "RefreshToken[***]";
    }
}
