package br.com.encurtador.identity.application;

import br.com.encurtador.security.UnknownSubjectException;
import br.com.encurtador.security.UserIdResolver;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * O {@code sub} dos tokens que este provedor emite é o id do usuário em texto (ADR-0001, D9). Só este módulo conhece
 * esse formato: o módulo de segurança apenas valida o token (ADR-0002, D3).
 */
@Component
class LocalUserIdResolver implements UserIdResolver {

    @Override
    public long resolve(String subject, Map<String, Object> claims) {
        if (!isUserId(subject)) {
            throw new UnknownSubjectException("O assunto do token não é um id de usuário.");
        }
        return Long.parseLong(subject);
    }

    static boolean isUserId(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            return Long.parseLong(subject) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
