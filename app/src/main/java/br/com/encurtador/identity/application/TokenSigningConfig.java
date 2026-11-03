package br.com.encurtador.identity.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;

/**
 * Assinatura HS256 dos access tokens que este provedor emite (ADR-0001, D9), com a mesma chave que o módulo de
 * segurança usa para validar.
 */
@Configuration(proxyBeanMethods = false)
class TokenSigningConfig {

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }
}
