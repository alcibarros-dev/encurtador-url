package br.com.encurtador.clicks;

import br.com.encurtador.support.ClickTopic;
import br.com.encurtador.support.RedirectClient;
import br.com.encurtador.support.RedirectTest;
import br.com.encurtador.support.TestLinks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.ConsumerFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O redirecionamento não espera pelo Kafka (ADR-0001, RNF02 e D8; cenário 6 do D14, regressão de P2). O broker é
 * pausado: continua aceitando conexões, mas não responde, o pior caso para quem esperasse por ele.
 */
@RedirectTest
class KafkaDegradationIntegrationTest {

    private static final int MEASURED = 100;

    @LocalServerPort
    int port;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    TestLinks links;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Test
    void redirectStaysFastWithKafkaHangingAndTheClicksArriveLater() {
        TestLinks.Link link = links.active("https://exemplo.com/");
        RedirectClient client = new RedirectClient(port);
        assertThat(client.get("/" + link.code()).status()).isEqualTo(302);

        List<Duration> latencies = new ArrayList<>();
        pause();
        try {
            for (int i = 0; i < MEASURED; i++) {
                long start = System.nanoTime();
                assertThat(client.get("/" + link.code()).status()).isEqualTo(302);
                latencies.add(Duration.ofNanos(System.nanoTime() - start));
            }
        } finally {
            unpause();
        }

        Collections.sort(latencies);
        assertThat(latencies.get((int) Math.ceil(MEASURED * 0.99) - 1)).isLessThan(Duration.ofMillis(50));
        // A pausa foi menor que o delivery.timeout.ms do produtor: nenhum clique se perdeu.
        assertThat(new ClickTopic(consumers).recordsOf(link.id(), Duration.ofSeconds(60),
                records -> records.size() >= MEASURED + 1)).hasSize(MEASURED + 1);
    }

    private void pause() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpause() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }
}
