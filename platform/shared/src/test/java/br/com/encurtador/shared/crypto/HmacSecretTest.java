package br.com.encurtador.shared.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSecretTest {

    @Test
    void acceptsSecretWithAtLeast32Bytes() {
        String secret = "  " + Base64.getEncoder().encodeToString(new byte[32]) + "\n";

        assertThat(HmacSecret.fromBase64(secret).getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void rejectsSecretShorterThan32BytesAfterDecoding() {
        String secret = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> HmacSecret.fromBase64(secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tem 31");
    }

    @Test
    void rejectsValueThatIsNotBase64() {
        assertThatThrownBy(() -> HmacSecret.fromBase64("isto não é base64!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }
}
