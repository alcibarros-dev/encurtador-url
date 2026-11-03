package br.com.encurtador.identity.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * O que a emissão do access token lê de {@code app.jwt.*} (ADR-0001, D9). A validação, no módulo de segurança, lê as
 * mesmas chaves: emissor e validador combinam emissor e audiência (ADR-0002, D10). A chave HMAC é um bean único
 * montado pela aplicação.
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtIssuerProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull @DurationMin(minutes = 1) Duration accessTokenTtl) {

    @Override
    public String toString() {
        return "JwtIssuerProperties[issuer=%s, audience=%s, accessTokenTtl=%s]"
                .formatted(issuer, audience, accessTokenTtl);
    }
}
