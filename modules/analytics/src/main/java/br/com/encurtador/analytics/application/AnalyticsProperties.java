package br.com.encurtador.analytics.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

/**
 * Cliques e estatísticas ({@code app.analytics.*}, ADR-0001, D8).
 *
 * @param topic             tópico dos cliques, publicados pelo serviço de redirecionamento (ADR-0006, D5)
 * @param deadLetterTopic   destino explícito das mensagens que o consumidor não consegue gravar
 * @param partitions        partições dos dois tópicos (o recoverer publica na mesma partição do registro original)
 * @param replicationFactor 1 no ambiente local
 * @param defaultZone       fuso das estatísticas quando a requisição não informa {@code tz}
 */
@Validated
@ConfigurationProperties("app.analytics")
public record AnalyticsProperties(
        @NotBlank String topic,
        @NotBlank String deadLetterTopic,
        @Min(1) int partitions,
        @Min(1) int replicationFactor,
        @NotNull ZoneId defaultZone) {
}
