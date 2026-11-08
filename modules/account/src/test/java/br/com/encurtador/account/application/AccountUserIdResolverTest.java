package br.com.encurtador.account.application;

import br.com.encurtador.account.infra.AccountRepository;
import dev.alcibarros.auth.security.UnknownSubjectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Validação do {@code sub} e cache do mapeamento (ADR-0003, D7); a criação no banco é coberta no app. */
class AccountUserIdResolverTest {

    private final AccountRepository accounts = mock(AccountRepository.class);

    @Test
    void knownSubjectIsReadOnceAndThenServedFromTheCache() {
        when(accounts.findIdBySubject("sub-1")).thenReturn(Optional.of(7L));
        var resolver = new AccountUserIdResolver(accounts);

        assertThat(resolver.resolve("sub-1", Map.of())).isEqualTo(7L);
        assertThat(resolver.resolve("sub-1", Map.of())).isEqualTo(7L);

        verify(accounts, times(1)).findIdBySubject("sub-1");
        verify(accounts, never()).provision(anyString());
    }

    @Test
    void unknownSubjectCreatesTheAccount() {
        when(accounts.findIdBySubject("novo")).thenReturn(Optional.empty());
        when(accounts.provision("novo")).thenReturn(42L);
        var resolver = new AccountUserIdResolver(accounts);

        assertThat(resolver.resolve("novo", Map.of())).isEqualTo(42L);
        assertThat(resolver.resolve("novo", Map.of())).isEqualTo(42L);

        verify(accounts, times(1)).provision("novo");
    }

    @Test
    void cacheKeepsOnlyTheMostRecentlyUsedSubjects() {
        when(accounts.findIdBySubject(anyString())).thenReturn(Optional.of(1L));
        var resolver = new AccountUserIdResolver(accounts, 2);

        resolver.resolve("a", Map.of());
        resolver.resolve("b", Map.of());
        resolver.resolve("a", Map.of());
        resolver.resolve("c", Map.of());   // descarta "b", o menos usado
        resolver.resolve("a", Map.of());
        resolver.resolve("b", Map.of());

        verify(accounts, times(1)).findIdBySubject("a");
        verify(accounts, times(2)).findIdBySubject("b");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankSubjectIsRejected(String subject) {
        var resolver = new AccountUserIdResolver(accounts);

        assertThatThrownBy(() -> resolver.resolve(subject, Map.of())).isInstanceOf(UnknownSubjectException.class);
    }

    @Test
    void subjectLongerThanTheColumnIsRejected() {
        var resolver = new AccountUserIdResolver(accounts);
        String tooLong = "x".repeat(AccountUserIdResolver.MAX_SUBJECT_LENGTH + 1);

        assertThatThrownBy(() -> resolver.resolve(tooLong, Map.of())).isInstanceOf(UnknownSubjectException.class);
        assertThat(resolver.resolve("x".repeat(AccountUserIdResolver.MAX_SUBJECT_LENGTH), Map.of())).isZero();
    }
}
