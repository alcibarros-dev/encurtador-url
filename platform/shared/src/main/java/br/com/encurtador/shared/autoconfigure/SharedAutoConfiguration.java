package br.com.encurtador.shared.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Núcleo técnico (ADR-0002, D11): registra os beans de {@code br.com.encurtador.shared} numa aplicação que não
 * escaneia esse pacote.
 * <p>
 * O app escaneia {@code br.com.encurtador} e já os encontra; a varredura daqui reencontra as mesmas classes e não as
 * registra de novo. O {@code @SpringBootApplication} exclui do scan as classes listadas como auto-configuração, como
 * esta.
 * <p>
 * O handler global de erros vem antes do {@code ProblemDetailsExceptionHandler} do Spring Boot.
 */
@AutoConfiguration(before = WebMvcAutoConfiguration.class)
@ComponentScan(basePackages = "br.com.encurtador.shared",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = AutoConfigurationExcludeFilter.class))
@ConfigurationPropertiesScan("br.com.encurtador.shared")
public class SharedAutoConfiguration {
}
