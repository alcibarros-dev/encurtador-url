package br.com.encurtador.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** BFF do painel (ADR-0004): a única origem que o navegador conhece. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
