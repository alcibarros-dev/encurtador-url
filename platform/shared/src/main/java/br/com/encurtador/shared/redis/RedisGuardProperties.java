package br.com.encurtador.shared.redis;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Disjuntor do Redis ({@code app.redis-guard.*}, ADR-0001, D7).
 *
 * @param failureThreshold falhas seguidas que abrem o disjuntor
 * @param openDuration     quanto tempo o Redis é ignorado antes de uma chamada de teste
 */
@Validated
@ConfigurationProperties("app.redis-guard")
public record RedisGuardProperties(
        @Min(1) int failureThreshold,
        @NotNull @DurationMin(millis = 100) Duration openDuration) {
}
