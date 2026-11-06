package br.com.encurtador.auth.credential;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void normalizesToNfc() {
        String decomposed = "café";
        String composed = "café";

        assertThat(decomposed).isNotEqualTo(composed);
        assertThat(PasswordPolicy.normalize(decomposed)).isEqualTo(composed);
        assertThat(PasswordPolicy.normalize(null)).isNull();
    }

    @Test
    void doesNotTrimPasswords() {
        assertThat(PasswordPolicy.normalize("  senha com espaços  ")).isEqualTo("  senha com espaços  ");
    }

    @Test
    void fitsEncoderUpTo72Utf8Bytes() {
        assertThat(PasswordPolicy.fitsEncoder("a".repeat(72))).isTrue();
        assertThat(PasswordPolicy.fitsEncoder("a".repeat(73))).isFalse();
        assertThat(PasswordPolicy.fitsEncoder("😀".repeat(18))).isTrue();
        assertThat(PasswordPolicy.fitsEncoder("😀".repeat(18) + "a")).isFalse();
    }
}
