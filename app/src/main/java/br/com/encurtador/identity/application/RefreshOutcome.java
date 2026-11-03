package br.com.encurtador.identity.application;

import br.com.encurtador.identity.domain.RefreshToken;

import java.util.UUID;

/**
 * Resultado da rotação. É um valor, não uma exceção: lançar dentro da transação desfaria a revogação da família
 * no rollback (a mesma classe de bug do P3, ADR-0001, D9).
 */
public sealed interface RefreshOutcome {

    RefreshOutcome INVALID = new Invalid();

    /** Token rotacionado: o antigo foi revogado e este é o novo da mesma sessão. */
    record Issued(long userId, RefreshToken refreshToken) implements RefreshOutcome {
    }

    /** Token inexistente ou expirado. */
    record Invalid() implements RefreshOutcome {
    }

    /** Token já rotacionado foi reapresentado: a sessão inteira foi revogada. */
    record Reused(long userId, UUID familyId) implements RefreshOutcome {
    }
}
