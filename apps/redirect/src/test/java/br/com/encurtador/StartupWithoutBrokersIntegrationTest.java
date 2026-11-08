package br.com.encurtador;

import br.com.encurtador.support.RedirectClient;
import br.com.encurtador.support.RedirectTest;
import br.com.encurtador.support.TestLinks;
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
import static org.awaitility.Awaitility.await;

/**
 * O serviço sobe e redireciona com o Redis e o Kafka fora, só com o PostgreSQL (ADR-0001, RNF02; cenário 15 do D14).
 * Sobe uma segunda instância, que aponta para portas sem ninguém escutando, e sem o Flyway, como em produção.
 */
@RedirectTest
class StartupWithoutBrokersIntegrationTest {

    @Autowired
    PostgreSQLContainer postgres;

    @Autowired
    TestLinks links;

    @Test
    void startsReadyAndRedirectsWithRedisAndKafkaDown() throws IOException {
        String code = links.active("https://exemplo.com/").code();

        long start = System.nanoTime();
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(RedirectApplication.class)
                .profiles("test")
                // Argumentos de linha de comando: vencem o application.yml, ao contrário das propriedades padrão.
                .run("--server.port=0",
                        "--spring.flyway.enabled=false",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=127.0.0.1",
                        "--spring.data.redis.port=" + closedPort(),
                        "--spring.kafka.bootstrap-servers=127.0.0.1:" + closedPort())) {
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(60));
            RedirectClient client = new RedirectClient(((WebServerApplicationContext) app).getWebServer().getPort());
            RedirectClient management = new RedirectClient(Integer.parseInt(
                    app.getEnvironment().getRequiredProperty("local.management.port")));

            assertThat(client.get("/readyz").status()).isEqualTo(200);
            assertThat(client.get("/" + code).status()).isEqualTo(302);

            // O clique não sai (max.block.ms sem metadados): a publicação aparece como DOWN, a readiness não muda.
            await().atMost(Duration.ofSeconds(20)).until(() -> "DOWN".equals(management.get("/actuator/health")
                    .body().path("components").path("kafkaPublish").path("status").asString()));
            assertThat(client.get("/readyz").status()).isEqualTo(200);
        }
    }

    /** Porta livre agora; depois de fechada, a conexão é recusada. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
