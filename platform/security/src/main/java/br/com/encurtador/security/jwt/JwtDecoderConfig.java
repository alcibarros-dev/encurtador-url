package br.com.encurtador.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;

/**
 * Validação de access tokens RS256 pelas chaves públicas do emissor (ADR-0003, D6), com o {@link Clock} da aplicação
 * (ADR-0001, D9). O {@code security} só valida: quem traduz o {@code sub} é o {@code UserIdResolver} (ADR-0002, D4).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtValidationProperties.class)
class JwtDecoderConfig {

    /** Bean próprio para que o contexto o feche no desligamento. */
    @Bean
    JwksKeySource jwksKeySource(JwtValidationProperties properties) {
        return new JwksKeySource(properties);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtValidationProperties properties, JwksKeySource keys, Clock clock) {
        return decoder(properties, keys, clock);
    }

    static NimbusJwtDecoder decoder(JwtValidationProperties jwt, JwksKeySource keys, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(jwt.clockSkew());
        timestamps.setClock(clock);
        // O Spring Authorization Server não grava o typ; outros emissores usam JWT ou at+jwt (RFC 9068). O aud já
        // impede que um id_token passe por access token.
        JwtTypeValidator type = new JwtTypeValidator("JWT", "at+jwt");
        type.setAllowEmpty(true);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(jwt.issuer()),
                new JwtAudienceValidator(jwt.audience()),
                type));
        return decoder;
    }
}
