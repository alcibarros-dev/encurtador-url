package br.com.encurtador.security.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import java.time.Clock;

/**
 * Validação de access tokens HS256, com o mesmo segredo e o mesmo {@link Clock} da emissão (ADR-0001, D9). O
 * {@code security} só valida: quem emite é o provedor de identidade, e quem traduz o {@code sub} é o
 * {@code UserIdResolver} dele (ADR-0002, D4).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtValidationProperties.class)
class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(JwtValidationProperties properties, SecretKey jwtSigningKey, Clock clock) {
        return decoder(properties, jwtSigningKey, clock);
    }

    static NimbusJwtDecoder decoder(JwtValidationProperties jwt, SecretKey key, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(jwt.clockSkew());
        timestamps.setClock(clock);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(jwt.issuer()),
                new JwtAudienceValidator(jwt.audience()),
                JwtTypeValidator.jwt()));
        return decoder;
    }
}
