package br.com.encurtador.shared.security;

import br.com.encurtador.shared.error.ProblemResponseWriter;
import br.com.encurtador.shared.ratelimit.RateLimitFilter;
import br.com.encurtador.shared.ratelimit.RateLimitProperties;
import br.com.encurtador.shared.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadeia de segurança stateless com JWT (ADR-0001, D9). As rotas de cada módulo entram na fase correspondente;
 * tudo que não foi liberado explicitamente é negado.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemResponseWriter problemWriter,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimits,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {
        var entryPoint = new ProblemDetailAuthenticationEntryPoint(problemWriter);
        var accessDeniedHandler = new ProblemDetailAccessDeniedHandler(problemWriter);

        // Depois da autenticação por Bearer, para os limites por usuário (D11).
        http.addFilterAfter(new RateLimitFilter(rateLimiter, rateLimits, problemWriter),
                BearerTokenAuthenticationFilter.class);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // Actuator completo só existe na porta de gestão, que não é publicada externamente (D13).
                    auth.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
                    // /livez e /readyz na porta pública.
                    auth.requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
                            .permitAll();
                    auth.requestMatchers("/error").permitAll();
                    if (apiDocsEnabled) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    }
                    // Refresh e logout se autenticam pelo refresh token no corpo, não por Bearer.
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                            "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll();
                    auth.requestMatchers("/api/v1/**").authenticated();
                    // Redirecionamento: um único segmento; o padrão do controller filtra o resto (D4).
                    auth.requestMatchers(HttpMethod.GET, "/*").permitAll();
                    auth.requestMatchers(HttpMethod.HEAD, "/*").permitAll();
                    auth.anyRequest().denyAll();
                })
                // Erros de segurança em ProblemDetail, tanto na validação do token quanto na autorização.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        // O Spring Security 7 publica a metadata da RFC 9728 em /.well-known/oauth-protected-resource
                        // e a anuncia no WWW-Authenticate. O padrão declara tokens presos a certificado (mTLS), o que
                        // não vale aqui.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                builder -> builder.resourceName("Encurtador de URLs")
                                        .tlsClientCertificateBoundAccessTokens(false))));
        return http.build();
    }
}
