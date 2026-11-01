package br.com.encurtador.redirect.application;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Validade das entradas do cache de resolução ({@code app.cache.*}, ADR-0001, D7).
 *
 * @param maxTtl       teto de uma entrada ativa; também é a maior defasagem possível se uma invalidação falhar
 * @param tombstoneTtl validade dos tombstones de link expirado ou removido
 */
@Validated
@ConfigurationProperties("app.cache")
public record LinkCacheProperties(
        @NotNull @DurationMin(seconds = 1) Duration maxTtl,
        @NotNull @DurationMin(seconds = 1) Duration tombstoneTtl) {
}
