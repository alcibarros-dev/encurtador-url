package br.com.encurtador.bff.proxy;

import br.com.encurtador.bff.config.BffProperties;
import br.com.encurtador.bff.config.HttpClients;
import br.com.encurtador.bff.config.OAuthClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP do repasse ao app (ADR-0004, D5): o {@code OAuth2ClientHttpRequestInterceptor} acrescenta o access
 * token da sessão e o renova pelo refresh token quando ele vence; os timeouts são curtos.
 */
@Configuration(proxyBeanMethods = false)
class ApiProxyConfig {

    @Bean
    RestClient appRestClient(OAuth2AuthorizedClientManager authorizedClients,
                             BffProperties properties) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(authorizedClients);
        interceptor.setClientRegistrationIdResolver(request -> OAuthClientConfig.REGISTRATION_ID);
        return RestClient.builder()
                .requestFactory(HttpClients.requestFactory(properties))
                .requestInterceptor(interceptor)
                // A resposta do app volta como veio, com qualquer status.
                .defaultStatusHandler(status -> false, (request, response) -> { })
                .build();
    }
}
