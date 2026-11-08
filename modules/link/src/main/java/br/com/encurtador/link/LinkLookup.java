package br.com.encurtador.link;

import java.util.List;
import java.util.Optional;

/**
 * Consulta de links do dono para os outros módulos (ADR-0001, D2). O redirecionamento não passa por aqui: ele roda
 * em serviço próprio e lê a tabela direto (ADR-0006, D2).
 */
public interface LinkLookup {

    /**
     * Link não removido do dono, expirado ou não. Link de outro usuário fica indistinguível de link inexistente
     * (D9).
     */
    Optional<LinkSnapshot> findOwned(String code, long ownerId);

    /**
     * Todos os links não removidos do dono, ativos e expirados, em qualquer ordem: as estatísticas do usuário
     * (ADR-0004, D7). Só os ativos têm teto; os expirados se acumulam.
     */
    List<LinkSnapshot> ownedLinks(long ownerId);
}
