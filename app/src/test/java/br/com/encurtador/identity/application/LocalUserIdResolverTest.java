package br.com.encurtador.identity.application;

import br.com.encurtador.security.UnknownSubjectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalUserIdResolverTest {

    private final LocalUserIdResolver resolver = new LocalUserIdResolver();

    @Test
    void readsTheUserIdFromTheSubject() {
        assertThat(resolver.resolve("42", Map.of())).isEqualTo(42L);
    }

    @Test
    void rejectsSubjectsThatAreNotUserIds() {
        assertThatThrownBy(() -> resolver.resolve("fulano@exemplo.com", Map.of()))
                .isInstanceOf(UnknownSubjectException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "42", "9223372036854775807"})
    void acceptsPositiveLongSubjects(String subject) {
        assertThat(LocalUserIdResolver.isUserId(subject)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "-1", "abc", "1.5", "9223372036854775808", "fulano@exemplo.com"})
    void rejectsAnythingElse(String subject) {
        assertThat(LocalUserIdResolver.isUserId(subject)).isFalse();
    }
}
