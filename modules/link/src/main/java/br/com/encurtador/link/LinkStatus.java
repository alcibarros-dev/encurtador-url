package br.com.encurtador.link;

/**
 * Status gravado de um link. {@code EXPIRED} não existe aqui: é derivado de {@code expiresAt} no momento da leitura,
 * e nada é escrito no caminho de leitura (ADR-0001, D5; corrige P3).
 */
public enum LinkStatus {
    ACTIVE,
    DELETED
}
