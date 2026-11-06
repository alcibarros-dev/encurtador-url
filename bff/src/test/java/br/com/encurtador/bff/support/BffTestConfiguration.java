package br.com.encurtador.bff.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis real para o rate limit e servidores falsos para o app e o auth-server (ADR-0004, D11). São beans: o cache de
 * contexto do Spring os reaproveita entre as classes de teste.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BffTestConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:8.8.4-alpine")
                .withCommand("redis-server", "--save", "", "--appendonly", "no")
                .withExposedPorts(6379);
    }

    @Bean(destroyMethod = "close")
    FakeServer fakeApp() {
        return new FakeServer();
    }

    @Bean(destroyMethod = "close")
    FakeServer fakeAuthServer() {
        return new FakeServer();
    }

    @Bean
    DynamicPropertyRegistrar fakeServerProperties(FakeServer fakeApp, FakeServer fakeAuthServer) {
        return registry -> {
            registry.add("bff.api-url", () -> fakeApp.uri().toString());
            // O endereço público só aparece em redirecionamentos; o interno recebe o token e a revogação.
            registry.add("bff.auth-public-url", () -> "http://auth.test");
            registry.add("bff.auth-internal-url", () -> fakeAuthServer.uri().toString());
        };
    }
}
