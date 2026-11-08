package br.com.encurtador.support;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

/**
 * Lê o tópico {@code link-clicks} como o analytics da API leria: do começo, num grupo de consumo novo a cada leitura.
 * O serviço só publica; quem grava os cliques é a API (ADR-0006, D5).
 */
public final class ClickTopic {

    public static final String TOPIC = "link-clicks";

    private final ConsumerFactory<String, String> consumers;

    public ClickTopic(ConsumerFactory<String, String> consumers) {
        this.consumers = consumers;
    }

    /** Os registros com a chave {@code linkId}, esperando até {@code done} aceitá-los. */
    public List<ConsumerRecord<String, String>> recordsOf(long linkId, Duration atMost,
                                                          Predicate<List<ConsumerRecord<String, String>>> done) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("teste-" + UUID.randomUUID(), null, null,
                overrides)) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(atMost).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (Long.toString(linkId).equals(record.key())) {
                        received.add(record);
                    }
                });
                return done.test(received);
            });
        }
        return received;
    }
}
