/**
 * Cache de resolução dos links no Redis, compartilhado entre duas aplicações (ADR-0001, D7; ADR-0006, D3 e D4): o
 * serviço de redirecionamento lê e preenche com {@code SET NX}; a API grava o tombstone de remoção depois do commit.
 * As duas usam esta mesma implementação, e por isso a chave {@code link:v1:{code}} e o formato JSON nunca divergem.
 * <p>
 * API pública: {@link br.com.encurtador.linkcache.LinkCache}, {@link br.com.encurtador.linkcache.CachedLink} e
 * {@link br.com.encurtador.linkcache.LinkCacheProperties}. O subpacote {@code redis} é interno.
 */
@ApplicationModule(displayName = "Link cache", allowedDependencies = "shared")
package br.com.encurtador.linkcache;

import org.springframework.modulith.ApplicationModule;
