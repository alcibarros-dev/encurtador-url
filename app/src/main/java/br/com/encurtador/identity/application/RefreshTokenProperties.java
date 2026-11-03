package br.com.encurtador.identity.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Validade dos refresh tokens e expurgo ({@code app.refresh-token.*}, ADR-0001, D9).
 *
 * @param ttl         validade de cada token
 * @param sessionTtl  validade absoluta da sessão (família), contada a partir do login
 * @param purgeGrace  há quanto tempo um token precisa ter expirado para ser apagado
 * @param purgeCron   horário do expurgo, em UTC
 */
@Validated
@ConfigurationProperties("app.refresh-token")
public record RefreshTokenProperties(
        @NotNull @DurationMin(minutes = 1) Duration ttl,
        @NotNull @DurationMin(minutes = 1) Duration sessionTtl,
        @NotNull Duration purgeGrace,
        @NotBlank String purgeCron) {
}
