package br.com.encurtador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Serviço de redirecionamento (ADR-0006). O código fica nesta aplicação, no pacote {@code redirect}; dos módulos do
 * repositório, só entram os contratos com a API ({@code link-cache} e {@code clicks}) e a plataforma.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RedirectApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedirectApplication.class, args);
    }
}
