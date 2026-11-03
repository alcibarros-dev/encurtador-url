package br.com.encurtador.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * O que a validação do token lê de {@code app.jwt.*}. O provedor de identidade lê as mesmas chaves para emitir: emissor
 * e validador combinam emissor e audiência (ADR-0002, D10). A chave HMAC não é lida aqui: é um bean único montado pela
 * aplicação, para que emissão e validação usem o mesmo valor.
 *
 * @param clockSkew tolerância para diferença de relógio entre emissão e validação
 */
@Validated
@ConfigurationProperties("app.jwt")
record JwtValidationProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration clockSkew) {

    @Override
    public String toString() {
        return "JwtValidationProperties[issuer=%s, audience=%s, clockSkew=%s]".formatted(issuer, audience, clockSkew);
    }
}
