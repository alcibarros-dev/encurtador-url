package br.com.encurtador.ratelimit.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Rate limit (ADR-0002, D5 e D11): registra os beans de {@code br.com.encurtador.ratelimit} numa aplicação que não
 * escaneia esse pacote.
 * <p>
 * O app escaneia {@code br.com.encurtador} e já os encontra; a varredura daqui reencontra as mesmas classes e não as
 * registra de novo. O {@code @SpringBootApplication} exclui do scan as classes listadas como auto-configuração, como
 * esta.
 */
@AutoConfiguration
@ComponentScan(basePackages = "br.com.encurtador.ratelimit",
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM,
                classes = AutoConfigurationExcludeFilter.class))
@ConfigurationPropertiesScan("br.com.encurtador.ratelimit")
public class RateLimitAutoConfiguration {
}
