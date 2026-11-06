package br.com.encurtador.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * O painel como cliente confidencial do auth-server (ADR-0004, D4). O registro é montado em código, e não por
 * {@code issuer-uri}: a descoberta no startup impediria o BFF de subir sem o auth-server e devolveria endereços
 * públicos, inalcançáveis de dentro do container. O navegador vai ao endereço público (autorização e logout); o BFF
 * chama o interno (token e JWKS).
 */
@Configuration(proxyBeanMethods = false)
public class OAuthClientConfig {

    public static final String REGISTRATION_ID = "encurtador";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(BffProperties properties) {
        return new InMemoryClientRegistrationRepository(registration(properties));
    }

    static ClientRegistration registration(BffProperties properties) {
        String publicUrl = withoutTrailingSlash(properties.authPublicUrl().toString());
        String internalUrl = withoutTrailingSlash(properties.authInternalUrl().toString());
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "email")
                .authorizationUri(publicUrl + "/oauth2/authorize")
                .tokenUri(internalUrl + "/oauth2/token")
                .jwkSetUri(internalUrl + "/oauth2/jwks")
                // O e-mail vem no id_token (ADR-0003, D5): sem userinfo, uma chamada a menos no login.
                .userNameAttributeName("sub")
                .issuerUri(publicUrl)
                .providerConfigurationMetadata(Map.of("end_session_endpoint", publicUrl + "/connect/logout"))
                // O Spring Security só liga o PKCE sozinho para cliente público.
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
    }

    /** Os tokens ficam na sessão do BFF, que é em memória (ADR-0004, D4): nunca no navegador nem no Redis. */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /** Troca do código pelo token no login, com timeouts curtos. */
    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient(
            BffProperties properties) {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(tokenRestClient(properties));
        return client;
    }

    /** Renova o access token pelo refresh token, sem rotação (ADR-0004, D4), antes de cada repasse ao app. */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                          OAuth2AuthorizedClientRepository authorizedClients,
                                                          BffProperties properties) {
        OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refresh = refreshTokenResponseClient(properties);
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(configurer -> configurer.accessTokenResponseClient(refresh))
                .build();
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    private static OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient(
            BffProperties properties) {
        var client = new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(tokenRestClient(properties));
        return client;
    }

    /** Mesmos conversores e tratamento de erro do cliente padrão do Spring Security, com timeouts. */
    static RestClient tokenRestClient(BffProperties properties) {
        return RestClient.builder()
                .requestFactory(HttpClients.requestFactory(properties))
                .messageConverters(List.of(new FormHttpMessageConverter(),
                        new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
