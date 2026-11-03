/**
 * Links curtos: criação com código gerado ou alias, listagem, consulta e remoção (ADR-0001, D3, D5 e D10).
 * <p>
 * API pública: {@link br.com.encurtador.link.LinkLookup} (consulta por código),
 * {@link br.com.encurtador.link.LinkSnapshot}, {@link br.com.encurtador.link.LinkStatus} e o evento
 * {@link br.com.encurtador.link.LinkDeleted}. O módulo não conhece o cache do redirecionamento: quem o mantém reage
 * ao evento.
 */
@ApplicationModule(displayName = "Link", allowedDependencies = {"security", "ratelimit", "shared"})
package br.com.encurtador.link;

import org.springframework.modulith.ApplicationModule;
