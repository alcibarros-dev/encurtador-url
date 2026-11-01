package br.com.encurtador.link.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasTest {

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "meu-link", "Promo_2026", "API-docs", "abcdefghijklmnopqrstuvwxyz012345"})
    void acceptsWellFormedAliases(String value) {
        assertThat(new Alias(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc", "abcdefghijklmnopqrstuvwxyz0123456", "com espaço", "ação", "a/b/c", "a.b.c",
            "abc%20"})
    void rejectsMalformedAliases(String value) {
        assertThatThrownBy(() -> new Alias(value))
                .isInstanceOf(InvalidValueException.class)
                .hasMessageStartingWith("deve ter de 4 a 32 caracteres");
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ADMIN", "Swagger-UI", "actuator", "livez", "readyz", "error", "assets"})
    void rejectsReservedWordsRegardlessOfCase(String value) {
        assertThatThrownBy(() -> new Alias(value)).hasMessage("é uma palavra reservada");
    }
}
