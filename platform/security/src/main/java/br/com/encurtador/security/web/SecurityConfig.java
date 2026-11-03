package br.com.encurtador.security.web;

import br.com.encurtador.security.PublicRoutes;
import br.com.encurtador.shared.error.ProblemResponseWriter;
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
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Cadeia de segurança stateless com JWT (ADR-0001, D9). As rotas públicas de negócio vêm dos módulos
 * ({@link PublicRoutes}, ADR-0002, D4); aqui ficam só as de infraestrutura. Tudo que não foi liberado explicitamente
 * exige token em {@code /api/v1/**} e é negado no resto.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ProblemResponseWriter problemWriter,
            List<PublicRoutes> publicRoutes,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {
        var entryPoint = new ProblemDetailAuthenticationEntryPoint(problemWriter);
        var accessDeniedHandler = new ProblemDetailAccessDeniedHandler(problemWriter);

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
                    publicRoutes.stream().flatMap(routes -> routes.routes().stream()).forEach(route ->
                            auth.requestMatchers(HttpMethod.valueOf(route.method()), route.pattern()).permitAll());
                    auth.requestMatchers("/api/v1/**").authenticated();
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
