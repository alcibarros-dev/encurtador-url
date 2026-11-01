package br.com.encurtador.shared.security;

import br.com.encurtador.shared.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import java.time.Clock;

/**
 * Emissão e validação de access tokens HS256 com o mesmo segredo e o mesmo {@link Clock} (ADR-0001, D9).
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(AppProperties properties) {
        return NimbusJwtEncoder.withSecretKey(signingKey(properties)).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(AppProperties properties, Clock clock) {
        return decoder(properties.jwt(), signingKey(properties), clock);
    }

    static NimbusJwtDecoder decoder(AppProperties.Jwt jwt, SecretKey key, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(jwt.clockSkew());
        timestamps.setClock(clock);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(jwt.issuer()),
                new JwtAudienceValidator(jwt.audience()),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, CurrentUser::isUserId),
                JwtTypeValidator.jwt()));
        return decoder;
    }

    private static SecretKey signingKey(AppProperties properties) {
        return HmacSecret.fromBase64(properties.jwt().secret());
    }
}
