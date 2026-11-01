package br.com.encurtador.link.infra;

import java.time.Instant;
import java.util.Optional;

/**
 * Escritas em SQL explícito e o que precisa rodar na mesma transação que elas (ADR-0001, D3, D5 e D6). Fragmento do
 * {@link LinkRepository}.
 */
public interface LinkWrites {

    /**
     * Serializa as criações do mesmo usuário até o fim da transação. É um advisory lock: não toca a tabela
     * {@code users}, que pertence a outro módulo.
     */
    void lockOwner(long ownerId);

    /**
     * Links ativos e não expirados. Precisa vir depois do {@link #lockOwner}: em READ COMMITTED cada instrução tem
     * snapshot próprio e enxerga o que a criação anterior do mesmo usuário gravou (corrige P12).
     */
    long countActive(long ownerId, Instant now);

    /** @return o id do link, ou vazio se o código já existe (sem abortar a transação, o que permite repetir) */
    Optional<Long> insertIfCodeAvailable(String code, String targetUrl, long ownerId, boolean customAlias,
                                         Instant createdAt, Instant expiresAt);

    /** Remoção lógica: o código fica reservado para sempre (corrige P11). @return linhas alteradas (0 ou 1) */
    int markDeleted(String code, long ownerId, Instant now);
}
