package br.com.encurtador.auth.credential;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressTest {

    @Test
    void trimsAndLowercases() {
        assertThat(EmailAddress.normalize("  Fulano.Silva@Exemplo.COM\t")).isEqualTo("fulano.silva@exemplo.com");
    }

    @Test
    void lowercasingIgnoresTheDefaultLocale() {
        // Em turco, "I".toLowerCase() vira "ı" (sem ponto); com Locale.ROOT continua "i".
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(EmailAddress.normalize("ADMIN@EXEMPLO.COM")).isEqualTo("admin@exemplo.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void keepsNullForValidation() {
        assertThat(EmailAddress.normalize(null)).isNull();
    }
}
