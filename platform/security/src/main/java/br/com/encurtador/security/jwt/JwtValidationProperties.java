package br.com.encurtador.security.jwt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * O que a validação do token lê de {@code app.jwt.*} (ADR-0003, D6). O app só conhece as chaves públicas do emissor,
 * publicadas no JWKS: quem emite é o servidor de autorização.
 *
 * @param issuer    valor exigido no {@code iss}: a URL pública do servidor de autorização
 * @param audience  valor exigido no {@code aud}
 * @param jwkSetUri endereço do JWKS; pode ser interno (no compose, {@code http://auth-server:8090/oauth2/jwks}),
 *                  diferente do emissor
 * @param clockSkew tolerância para diferença de relógio entre emissão e validação
 * @param jwks      cache das chaves públicas
 */
@Validated
@ConfigurationProperties("app.jwt")
record JwtValidationProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull URI jwkSetUri,
        @NotNull Duration clockSkew,
        @Valid @DefaultValue Jwks jwks) {

    /**
     * Cache das chaves públicas (ADR-0003, D6). Os valores padrão aguentam o servidor de autorização fora por um dia
     * sem recusar tokens assinados com chaves já conhecidas.
     *
     * @param cacheTtl           validade das chaves em cache; uma chave retirada some do app nesse prazo
     * @param refreshAhead       renova o cache quando faltar isso para ele expirar, sem bloquear requisições
     * @param minRefreshInterval janela do limite de buscas fora de hora (até duas por janela), como as de um
     *                           {@code kid} desconhecido
     * @param outageTolerance    por quanto tempo as últimas chaves obtidas continuam valendo com o JWKS fora
     * @param connectTimeout     tempo máximo para abrir a conexão com o JWKS
     * @param readTimeout        tempo máximo para ler a resposta do JWKS
     */
    record Jwks(
            @DefaultValue("15m") @NotNull Duration cacheTtl,
            @DefaultValue("1m") @NotNull Duration refreshAhead,
            @DefaultValue("30s") @NotNull Duration minRefreshInterval,
            @DefaultValue("24h") @NotNull Duration outageTolerance,
            @DefaultValue("500ms") @NotNull Duration connectTimeout,
            @DefaultValue("2s") @NotNull Duration readTimeout) {

        /** Tempo que uma requisição espera por uma renovação já em andamento. */
        Duration refreshTimeout() {
            return connectTimeout.plus(readTimeout);
        }

        /** Exigência do cache do Nimbus: a renovação antecipada precisa caber dentro da validade. */
        @AssertTrue(message = "refresh-ahead mais connect-timeout e read-timeout precisa ser menor que cache-ttl")
        boolean isRefreshAheadWithinCacheTtl() {
            return cacheTtl == null || refreshAhead == null || connectTimeout == null || readTimeout == null
                    || refreshAhead.plus(refreshTimeout()).compareTo(cacheTtl) < 0;
        }
    }
}
