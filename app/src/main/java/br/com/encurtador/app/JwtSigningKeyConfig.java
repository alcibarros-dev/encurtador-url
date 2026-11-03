package br.com.encurtador.app;

import br.com.encurtador.shared.crypto.HmacSecret;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import javax.crypto.SecretKey;

/**
 * Chave HMAC dos access tokens, lida uma única vez de {@code app.jwt.secret} (ADR-0001, D9; ADR-0002, D10). O
 * provedor de identidade assina e o módulo de segurança valida com este mesmo bean, sem que um conheça o outro.
 * <p>
 * Ler a propriedade uma vez só importa: no compose, sem {@code APP_JWT_SECRET}, ela é {@code ${random.value}…}, e cada
 * leitura geraria um segredo diferente.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtSigningKeyConfig.JwtSecretProperties.class)
class JwtSigningKeyConfig {

    @Bean
    SecretKey jwtSigningKey(JwtSecretProperties properties) {
        return HmacSecret.fromBase64(properties.secret());
    }

    /** @param secret Base64 de pelo menos 32 bytes; o tamanho é verificado depois de decodificar ({@link HmacSecret}) */
    @Validated
    @ConfigurationProperties("app.jwt")
    record JwtSecretProperties(@NotBlank String secret) {

        @Override
        public String toString() {
            return "JwtSecretProperties[secret=***]";
        }
    }
}
