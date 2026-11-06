package br.com.encurtador.auth.config;

import br.com.encurtador.auth.credential.LoginBlockedException;
import br.com.encurtador.auth.credential.RateLimitedAuthenticationProvider;
import br.com.encurtador.auth.token.DeviceClientAuthenticationConverter;
import br.com.encurtador.auth.token.DeviceClientAuthenticationProvider;
import br.com.encurtador.ratelimit.RateLimiter;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Duas cadeias (ADR-0003, D2 e D9): a dos endpoints do protocolo (SAS), que não usa CSRF, e a das páginas (login,
 * cadastro, troca de senha, confirmação de dispositivo), com sessão e CSRF.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, RegisteredClientRepository clients,
                                                 AuthorizationServerSettings settings, AuthServerProperties panel)
            throws Exception {
        http.oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer
                            // Logout com um id_token que o servidor não conhece mais, como depois de uma troca de
                            // senha, que encerra as autorizações (ADR-0003, D9): a sessão daqui acaba do mesmo jeito,
                            // e o navegador volta ao endereço fixo do painel, nunca a um endereço da requisição.
                            .oidc(oidc -> oidc.logoutEndpoint(logout -> logout.errorResponseHandler(
                                    (request, response, exception) -> {
                                        new SecurityContextLogoutHandler().logout(request, response, null);
                                        response.sendRedirect(panel.panelDefaultUrl().toString());
                                    })))
                            // Absoluta, a partir do issuer: relativa, o SAS a montaria com o endereço da requisição.
                            .deviceAuthorizationEndpoint(device -> device.verificationUri(
                                    settings.getIssuer() + "/activate"))
                            .clientAuthentication(client -> client
                                    .authenticationConverter(new DeviceClientAuthenticationConverter(
                                            settings.getDeviceAuthorizationEndpoint()))
                                    .authenticationProvider(new DeviceClientAuthenticationProvider(clients)));
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                // Navegador sem sessão vai para a página de login; clientes recebem o erro do protocolo.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain pagesChain(HttpSecurity http, UserDetailsService users, PasswordEncoder passwordEncoder,
                                   RateLimiter limiter) throws Exception {
        http.authorizeHttpRequests(authorize -> {
                    // Actuator completo só na porta de gestão; /livez e /readyz na pública (ADR-0001, D13).
                    authorize.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
                    authorize.requestMatchers(
                            EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class)).permitAll();
                    authorize.requestMatchers("/login", "/register", "/css/**", "/favicon.ico", "/error").permitAll();
                    authorize.anyRequest().authenticated();
                })
                // Só este provider, sem o gerenciador pai que o Spring Boot monta com o UserDetailsService: o
                // ProviderManager passaria adiante um login recusado aqui, e o pai o autenticaria sem os limites.
                .authenticationManager(new ProviderManager(
                        new RateLimitedAuthenticationProvider(users, passwordEncoder, limiter)))
                .formLogin(form -> form.loginPage("/login").failureHandler(loginFailure()).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    /** bcrypt hoje, com prefixo no hash: permite trocar de algoritmo sem invalidar senhas (ADR-0001, D9). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Só o Swagger UI do perfil local chama o endpoint de token de outra origem (ADR-0003, D10). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthServerCorsProperties cors) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!cors.allowedOrigins().isEmpty()) {
            var configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(cors.allowedOrigins());
            configuration.setAllowedMethods(List.of("POST"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            source.registerCorsConfiguration("/oauth2/token", configuration);
        }
        return source;
    }

    private static AuthenticationFailureHandler loginFailure() {
        return (request, response, exception) -> {
            String target = switch (exception) {
                case LoginBlockedException blocked -> "/login?blocked=" + blocked.retryAfterSeconds();
                case InternalAuthenticationServiceException ignored -> "/login?unavailable";
                default -> "/login?error";
            };
            response.sendRedirect(request.getContextPath() + target);
        };
    }
}
