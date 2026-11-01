package br.com.encurtador.shared.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Toda leitura de tempo passa por este {@link Clock}, que os testes substituem (ADR-0001, D2).
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
