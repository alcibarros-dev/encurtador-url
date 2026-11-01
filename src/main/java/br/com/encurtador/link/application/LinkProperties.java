package br.com.encurtador.link.application;

import br.com.encurtador.link.domain.ShortCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Geração de códigos, validade e cota dos links ({@code app.links.*}, ADR-0001, D3 e D5).
 *
 * @param codeLength       tamanho do código gerado, em caracteres base62
 * @param defaultTtl       validade quando o link é criado sem {@code expiresAt}
 * @param maxTtl           maior validade aceita
 * @param maxActivePerUser cota de links ativos e não expirados por usuário
 */
@Validated
@ConfigurationProperties("app.links")
public record LinkProperties(
        @Min(6) @Max(ShortCode.MAX_LENGTH) int codeLength,
        @NotNull @DurationMin(minutes = 1) Duration defaultTtl,
        @NotNull @DurationMin(days = 1) Duration maxTtl,
        @Min(1) int maxActivePerUser) {

    @AssertTrue(message = "default-ttl não pode ser maior que max-ttl")
    public boolean isDefaultTtlWithinMaxTtl() {
        return defaultTtl == null || maxTtl == null || defaultTtl.compareTo(maxTtl) <= 0;
    }
}
