package br.com.encurtador.shared.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserTest {

    @Test
    void readsTheUserIdFromTheSubject() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("42").build();

        assertThat(CurrentUser.id(jwt)).isEqualTo(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "42", "9223372036854775807"})
    void acceptsPositiveLongSubjects(String subject) {
        assertThat(CurrentUser.isUserId(subject)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "-1", "abc", "1.5", "9223372036854775808", "fulano@exemplo.com"})
    void rejectsAnythingElse(String subject) {
        assertThat(CurrentUser.isUserId(subject)).isFalse();
    }
}
