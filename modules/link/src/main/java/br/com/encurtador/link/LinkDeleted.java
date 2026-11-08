package br.com.encurtador.link;

/**
 * Publicado dentro da transação que remove um link. Quem guarda dados derivados dele (o tombstone no cache do serviço
 * de redirecionamento, gravado pela API) reage depois do commit, para nunca agir sobre uma remoção desfeita (ADR-0001,
 * §2.3 e D7; ADR-0006, D4).
 *
 * @param code código do link removido; continua reservado para sempre
 */
public record LinkDeleted(String code) {
}
