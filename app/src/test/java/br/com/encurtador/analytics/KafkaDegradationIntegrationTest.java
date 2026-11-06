package br.com.encurtador.analytics;

import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O redirecionamento não espera pelo Kafka (ADR-0001, RNF02 e D8; cenário 6 do D14, regressão de P2). O broker é
 * pausado: continua aceitando conexões, mas não responde, o pior caso para quem esperasse por ele.
 */
@IntegrationTest
class KafkaDegradationIntegrationTest {

    private static final int MEASURED = 100;

    @LocalServerPort
    int port;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    LinkService links;

    @Autowired
    TestAccounts accounts;

    @Autowired
    JdbcClient jdbc;

    @Test
    void redirectStaysFastWithKafkaHangingAndTheClicksArriveLater() {
        long owner = accounts.create().id();
        String code = links.create(owner, "https://exemplo.com/", null, null).code();
        ApiClient api = new ApiClient(port);
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);

        List<Duration> latencies = new ArrayList<>();
        pause();
        try {
            for (int i = 0; i < MEASURED; i++) {
                long start = System.nanoTime();
                assertThat(api.get("/" + code, null).status()).isEqualTo(302);
                latencies.add(Duration.ofNanos(System.nanoTime() - start));
            }
        } finally {
            unpause();
        }

        Collections.sort(latencies);
        assertThat(latencies.get((int) Math.ceil(MEASURED * 0.99) - 1)).isLessThan(Duration.ofMillis(50));
        // A pausa foi menor que o delivery.timeout.ms do produtor: nenhum clique se perdeu.
        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.sql("""
                        select count(*) from click_events
                         where link_id = (select id from links where code = :code)
                        """)
                .param("code", code).query(Long.class).single() == MEASURED + 1);
    }

    private void pause() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpause() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }
}
