package br.com.encurtador.bff.config;

import br.com.encurtador.bff.session.ReturnToAuthorizationRequestResolver;
import br.com.encurtador.bff.session.ReturnToSuccessHandler;
import br.com.encurtador.bff.web.CsrfCookieFilter;
import br.com.encurtador.bff.web.ProblemAccessDeniedHandler;
import br.com.encurtador.bff.web.ProblemAuthenticationEntryPoint;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Login OIDC com {@code returnTo}, sessão com cookie, CSRF para SPA e os cabeçalhos de segurança de D9 (ADR-0004,
 * D4 e D9). Os arquivos da SPA e as rotas dela são públicos; os dados só passam com sessão.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /** A política de D9: scripts só da própria origem; estilos em linha porque o Angular injeta {@code <style>}. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository registrations,
                                            CsrfTokenRepository csrfTokens, ProblemResponseWriter problems)
            throws Exception {
        var entryPoint = new ProblemAuthenticationEntryPoint(problems);
        http.authorizeHttpRequests(auth -> {
                    auth.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
                    auth.requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER,
                            HealthEndpoint.class)).permitAll();
                    auth.requestMatchers("/bff/register", "/bff/password").permitAll();
                    auth.requestMatchers("/api/**", "/bff/**").authenticated();
                    // A SPA (arquivos estáticos e as rotas de D6) e o início do login.
                    auth.anyRequest().permitAll();
                })
                .oauth2Login(login -> login
                        // Sem a página de login gerada pelo Spring Security: a página inicial da SPA tem o "Entrar".
                        .loginPage("/")
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(new ReturnToAuthorizationRequestResolver(registrations)))
                        .successHandler(new ReturnToSuccessHandler())
                        .failureUrl("/?erro=login"))
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(problems)))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    /**
     * Cookie de CSRF legível pelo JavaScript (o {@code HttpClient} do Angular o devolve em {@code X-XSRF-TOKEN}), com
     * {@code __Host-} e {@code Secure} fora do ambiente local.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository(BffProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(properties.cookies().csrfName());
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .secure(properties.cookies().secure())
                .sameSite("Lax"));
        return repository;
    }
}
