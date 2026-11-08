/**
 * Redirecionamento {@code GET|HEAD /{code}}: o caminho quente, que roda no serviço próprio {@code apps/redirect}
 * (ADR-0001, §2.1, D4 e D7; ADR-0006).
 * <p>
 * Resolve o código com cache-aside no Redis e nunca depende dele: erro, timeout ou disjuntor aberto viram leitura
 * direta, só de leitura, da tabela {@code links} da API. Cada {@code GET} que redireciona entrega um clique à fila em
 * memória que publica no Kafka, sem esperar por ela. Quem invalida o cache quando um link é removido é a API.
 */
@ApplicationModule(displayName = "Redirect", allowedDependencies = {"linkcache", "clicks", "ratelimit", "shared"})
package br.com.encurtador.redirect;

import org.springframework.modulith.ApplicationModule;
