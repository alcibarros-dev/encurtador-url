package br.com.encurtador;

import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A API sobe pronta com o Redis e o Kafka fora, só com o PostgreSQL (ADR-0001, RNF02; cenário 15 do D14). Sobe uma
 * segunda instância, que aponta para portas sem ninguém escutando. O redirecionamento sem brokers é testado no serviço
 * apps/redirect (ADR-0006, D7).
 */
@IntegrationTest
class StartupWithoutBrokersIntegrationTest {

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void startsReadyWithRedisAndKafkaDown() throws IOException {
        long start = System.nanoTime();
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(EncurtadorApplication.class)
                .profiles("test")
                // Argumentos de linha de comando: vencem o application.yml, ao contrário das propriedades padrão.
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=127.0.0.1",
                        "--spring.data.redis.port=" + closedPort(),
                        "--spring.kafka.bootstrap-servers=127.0.0.1:" + closedPort())) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(60));
            ApiClient api = new ApiClient(((WebServerApplicationContext) app).getWebServer().getPort());

            assertThat(api.get("/readyz", null).status()).isEqualTo(200);
        }
    }

    /** Porta livre agora; depois de fechada, a conexão é recusada. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
