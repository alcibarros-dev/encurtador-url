package br.com.encurtador.shared.autoconfigure;

import br.com.encurtador.shared.config.AppProperties;
import br.com.encurtador.shared.error.ProblemResponseWriter;
import br.com.encurtador.shared.redis.RedisGuard;
import br.com.encurtador.shared.time.UuidV7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uma aplicação que não escaneia {@code br.com.encurtador} recebe os beans do núcleo técnico pela auto-configuração
 * (ADR-0002, D11), como o servidor de autorização do ADR-0003.
 */
class SharedAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, SharedAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("app.base-url=http://localhost:8080",
                    "app.redis-guard.failure-threshold=5", "app.redis-guard.open-duration=5s");

    @Test
    void registersTheSharedBeansWithoutComponentScanOfTheApplication() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context).hasSingleBean(UuidV7Generator.class);
            assertThat(context).hasSingleBean(ProblemResponseWriter.class);
            assertThat(context).hasSingleBean(RedisGuard.class);
            assertThat(context).hasSingleBean(AppProperties.class);
            assertThat(context.getBeanNamesForType(Clock.class)).hasSize(1);
        });
    }
}
