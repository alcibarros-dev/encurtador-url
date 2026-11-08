package br.com.encurtador.redirect.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Publicação dos cliques ({@code app.clicks.*}, ADR-0001, D8; ADR-0006, D5).
 *
 * @param bufferCapacity tamanho da fila em memória entre o redirecionamento e o Kafka, por instância
 * @param topic          tópico dos cliques, o mesmo que o analytics da API consome; a chave é o {@code linkId}
 */
@Validated
@ConfigurationProperties("app.clicks")
public record ClickProperties(@Min(1) int bufferCapacity, @NotBlank String topic) {
}
