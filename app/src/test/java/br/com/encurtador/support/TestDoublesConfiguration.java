package br.com.encurtador.support;

import br.com.encurtador.link.application.RandomShortCodeGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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
}
