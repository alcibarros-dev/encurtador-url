package br.com.encurtador.identity.infra;

import java.time.Instant;
import java.util.Optional;

/**
 * Fragmento do {@link UserRepository} com o cadastro em SQL. A unicidade do e-mail é garantida pelo banco
 * ({@code ON CONFLICT}), sem consulta prévia: duas requisições simultâneas não criam duas contas.
 */
public interface UserInsertion {

    /** Id da conta criada, ou vazio se o e-mail já estiver em uso. */
    Optional<Long> insertIfEmailAvailable(String email, String passwordHash, Instant now);
}
