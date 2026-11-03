package br.com.encurtador.shared.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * Propriedades gerais da aplicação ({@code app.*}), validadas no startup para falhar cedo (ADR-0001, D15). As de cada
 * módulo ficam no módulo: {@code app.jwt.*} é lido pela validação do token e pela emissão (ADR-0002, D10).
 *
 * @param baseUrl prefixo do {@code shortUrl}; o encurtador recusa destinos para esse host
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotNull URI baseUrl) {
}
