package br.com.encurtador.link.application;

/**
 * Fonte de candidatos a código. A unicidade não é responsabilidade dela: quem decide é o índice único, no
 * {@code INSERT} (ADR-0001, D3). Interface para que os testes forcem colisões e a exaustão (D14, cenário 17).
 */
public interface ShortCodeGenerator {

    String next();
}
