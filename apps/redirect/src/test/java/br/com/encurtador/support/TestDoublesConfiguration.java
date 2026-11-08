package br.com.encurtador.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Dublês compartilhados por todos os testes de integração. São {@code @Primary}: vencem os beans reais sem criar um
 * segundo contexto (e um segundo conjunto de containers).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
