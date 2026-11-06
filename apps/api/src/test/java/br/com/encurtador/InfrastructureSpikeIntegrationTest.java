package br.com.encurtador;

import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.TestcontainersConfiguration;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Spike da F0 (ADR-0001, R3): a infraestrutura sobe com {@code @ServiceConnection} e responde, e o Toxiproxy
 * consegue simular um Redis "pendurado" (base dos testes de degradação da F3).
 */
@IntegrationTest
@Testcontainers
class InfrastructureSpikeIntegrationTest {

    private static final int TOXIPROXY_PORT = 8666;

    @Container
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(TestcontainersConfiguration.TOXIPROXY_IMAGE)
            .withNetwork(TestcontainersConfiguration.NETWORK);

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Test
    void postgresIsVersion18() {
        String version = jdbcClient.sql("show server_version").query(String.class).single();

        assertThat(version).startsWith("18.");
    }

    @Test
    void redisStoresValuesWithTtl() {
        redis.opsForValue().set("spike:chave", "valor", Duration.ofSeconds(30));

        assertThat(redis.opsForValue().get("spike:chave")).isEqualTo("valor");
        assertThat(redis.getExpire("spike:chave", TimeUnit.SECONDS)).isBetween(1L, 30L);
    }

    @Test
    void kafkaDeliversPublishedRecords() throws Exception {
        String topic = "spike-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(new NewTopic(topic, 1, (short) 1));

        kafkaTemplate.send(topic, "chave", "valor").get(10, TimeUnit.SECONDS);

        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("spike", null, null, overrides)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                return !received.isEmpty();
            });
            assertThat(received).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo("chave");
                assertThat(record.value()).isEqualTo("valor");
            });
        }
    }

    @Test
    void toxiproxySimulatesHangingRedis() throws Exception {
        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        Proxy proxy = client.createProxy("redis-spike", "0.0.0.0:" + TOXIPROXY_PORT,
                TestcontainersConfiguration.REDIS_ALIAS + ":6379");
        RedisClient redisClient = RedisClient.create(RedisURI.builder()
                .withHost(toxiproxy.getHost())
                .withPort(toxiproxy.getMappedPort(TOXIPROXY_PORT))
                .withTimeout(Duration.ofMillis(100))
                .build());
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            assertThat(connection.sync().ping()).isEqualTo("PONG");

            proxy.toxics().timeout("pendurado", ToxicDirection.DOWNSTREAM, 0);

            long start = System.nanoTime();
            assertThatThrownBy(() -> connection.sync().ping()).isInstanceOf(RedisCommandTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        } finally {
            redisClient.shutdown();
            proxy.delete();
        }
    }
}
