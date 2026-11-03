package br.com.encurtador.security.web;

import br.com.encurtador.security.CurrentUser;
import br.com.encurtador.security.UnknownSubjectException;
import br.com.encurtador.security.UserIdResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code @CurrentUser} delega ao provedor e transforma assunto desconhecido em token inválido (ADR-0002, D3). */
class CurrentUserArgumentResolverTest {

    private final UserIdResolver users = mock(UserIdResolver.class);
    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver(users);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsOnlyAnnotatedLongParameters() throws Exception {
        assertThat(resolver.supportsParameter(parameter(0))).isTrue();
        assertThat(resolver.supportsParameter(parameter(1))).isTrue();
        assertThat(resolver.supportsParameter(parameter(2))).isFalse();
        assertThat(resolver.supportsParameter(parameter(3))).isFalse();
    }

    @Test
    void resolvesTheUserThroughTheProvider() throws Exception {
        authenticate("42");
        when(users.resolve(eq("42"), anyMap())).thenReturn(42L);

        assertThat(resolver.resolveArgument(parameter(0), null, null, null)).isEqualTo(42L);
    }

    @Test
    void unknownSubjectIsAnInvalidToken() throws Exception {
        authenticate("fulano");
        when(users.resolve(eq("fulano"), anyMap())).thenThrow(new UnknownSubjectException("desconhecido"));

        assertThatThrownBy(() -> resolver.resolveArgument(parameter(0), null, null, null))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void withoutTokenTheRequestIsUnauthenticated() {
        assertThatThrownBy(() -> resolver.resolveArgument(parameter(0), null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject(subject).claims(c -> c.putAll(Map.of()))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MethodParameter parameter(int index) throws NoSuchMethodException {
        Method method = Probe.class.getDeclaredMethod("handle", long.class, Long.class, long.class, String.class);
        return new MethodParameter(method, index);
    }

    @SuppressWarnings("unused")
    private static final class Probe {

        void handle(@CurrentUser long primitive, @CurrentUser Long boxed, long notAnnotated,
                    @CurrentUser String wrongType) {
        }
    }
}
