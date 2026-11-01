package br.com.encurtador.shared.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Limites de requisições por alvo ({@code app.rate-limit.*}, ADR-0001, D11). Cada um é uma janela fixa.
 *
 * @param login          por IP
 * @param loginEmail     por e-mail normalizado
 * @param register       por IP
 * @param refresh        por IP
 * @param passwordChange por usuário (força bruta da senha atual com um token roubado)
 * @param linkCreation   por usuário
 * @param redirect       por IP
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @NotNull @Valid Limit login,
        @NotNull @Valid Limit loginEmail,
        @NotNull @Valid Limit register,
        @NotNull @Valid Limit refresh,
        @NotNull @Valid Limit passwordChange,
        @NotNull @Valid Limit linkCreation,
        @NotNull @Valid Limit redirect) {

    public record Limit(@Min(1) long requests, @NotNull @DurationMin(seconds = 1) Duration window) {
    }
}
