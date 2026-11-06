package br.com.encurtador.link;

import br.com.encurtador.link.domain.ShortCode;

import java.util.List;
import java.util.Optional;

/** Consulta de links por código para os outros módulos (ADR-0001, D2). */
public interface LinkLookup {

    /** Forma de todo código curto, gerado ou alias; o redirecionamento a usa como padrão da rota (D4). */
    String CODE_PATTERN = ShortCode.PATTERN;

    /** Inclui links removidos e expirados: cabe a quem consulta decidir o que fazer com eles. */
    Optional<LinkSnapshot> findByCode(String code);

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
