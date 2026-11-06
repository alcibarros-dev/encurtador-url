package br.com.encurtador.auth.token;

import br.com.encurtador.auth.config.AuthServerProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.ArrayList;
import java.util.List;

/** Chaves, autorizações e consentimentos do servidor de autorização (ADR-0003, D5 e D8). */
@Configuration(proxyBeanMethods = false)
class TokenConfig {

    /** Chave que assina os tokens; o {@code kid} dela vai no cabeçalho ({@link TokenClaimsCustomizer}). */
    @Bean
    CurrentSigningKey currentSigningKey(AuthServerProperties properties) {
        return new CurrentSigningKey(SigningKeys.load(properties.signingKey()));
    }

    /** O JWKS publica a chave atual e, durante a rotação, a anterior. Só a atual assina. */
    @Bean
    JWKSource<SecurityContext> jwkSource(CurrentSigningKey current, AuthServerProperties properties) {
        List<JWK> keys = new ArrayList<>();
        keys.add(current.key());
        if (properties.previousSigningKey() != null) {
            keys.add(SigningKeys.load(properties.previousSigningKey()));
        }
        return new ImmutableJWKSet<>(new JWKSet(keys));
    }

    /** Valida os access tokens que chegam ao endpoint {@code /userinfo}. */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /** Autorizações, códigos e tokens emitidos ficam na tabela {@code oauth2_authorization} do schema {@code auth}. */
    @Bean
    OAuth2AuthorizationService authorizationService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService() {
        return new UnrememberedConsentService();
    }
}
