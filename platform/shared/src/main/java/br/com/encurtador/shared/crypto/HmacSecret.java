package br.com.encurtador.shared.crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Converte o segredo configurado em chave HMAC-SHA256, exigindo pelo menos 32 bytes depois de decodificar
 * (ADR-0001, D9). Falhar aqui derruba o startup, que é o comportamento desejado.
 */
public final class HmacSecret {

    static final int MIN_BYTES = 32;

    private HmacSecret() {
    }

    public static SecretKey fromBase64(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.jwt.secret precisa estar em Base64.", e);
        }
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret precisa ter pelo menos %d bytes depois de decodificado (tem %d)."
                            .formatted(MIN_BYTES, bytes.length));
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
