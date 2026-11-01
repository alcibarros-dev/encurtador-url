package br.com.encurtador.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Propriedades da aplicação ({@code app.*}), validadas no startup para falhar cedo.
 * Novas seções entram junto com a fase que as usa (ADR-0001, D15).
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @NotNull URI baseUrl,
        @NotNull @Valid Jwt jwt) {

    public record Jwt(
            /* Base64 de pelo menos 32 bytes; o tamanho é verificado depois de decodificar (HmacSecret). */
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull @DurationMin(minutes = 1) Duration accessTokenTtl,
            @NotNull Duration clockSkew) {

        @Override
        public String toString() {
            return "Jwt[issuer=%s, audience=%s, accessTokenTtl=%s, clockSkew=%s]"
                    .formatted(issuer, audience, accessTokenTtl, clockSkew);
        }
    }
}
