package br.com.encurtador.support;

import br.com.encurtador.link.application.RandomShortCodeGenerator;
import br.com.encurtador.security.UserIdResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Dublês controláveis compartilhados por todos os testes de integração. São {@code @Primary}: vencem os beans reais
 * em todo ponto de injeção sem criar um segundo contexto (e um segundo conjunto de containers).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    ScriptedShortCodeGenerator scriptedShortCodeGenerator(RandomShortCodeGenerator random) {
        return new ScriptedShortCodeGenerator(random);
    }

    /** No lugar do auth-server: o app busca as chaves aqui (ADR-0003, D12). */
    @Bean
    TestJwks testJwks() {
        return new TestJwks();
    }

    @Bean
    DynamicPropertyRegistrar testJwksProperties(TestJwks jwks) {
        return registry -> registry.add("app.jwt.jwk-set-uri", () -> jwks.uri().toString());
    }

    @Bean
    TestTokens testTokens(MutableClock clock, @Value("${app.jwt.issuer}") String issuer,
                          @Value("${app.jwt.audience}") String audience) {
        return new TestTokens(clock, issuer, audience);
    }

    @Bean
    TestAccounts testAccounts(UserIdResolver resolver, TestTokens tokens) {
        return new TestAccounts(resolver, tokens);
    }
}
