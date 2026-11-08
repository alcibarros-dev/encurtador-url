package br.com.encurtador.support;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Infraestrutura real, nas mesmas versões do compose.yaml (ADR-0001, D14). Os containers são beans: o cache de
 * contexto do Spring os reaproveita entre classes de teste.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:18.6-alpine";
    public static final String REDIS_IMAGE = "redis:8.8.4-alpine";
    public static final String KAFKA_IMAGE = "apache/kafka:4.4.0";
    public static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.13.0";

    /** Rede compartilhada para que o Toxiproxy alcance o Redis pelo alias. */
    public static final Network NETWORK = Network.newNetwork();
    public static final String REDIS_ALIAS = "redis";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    /**
     * O schema é o das migrações da API (ADR-0006, D7). O Flyway usa conexão própria: o pool da aplicação é só de
     * leitura (D2) e recusaria as migrações.
     */
    @Bean
    FlywayConfigurationCustomizer writableFlywayConnection(PostgreSQLContainer postgres) {
        return configuration -> configuration.dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    @Bean
    TestLinks testLinks(PostgreSQLContainer postgres, MutableClock clock) {
        return new TestLinks(postgres, clock);
    }

    /** Sem {@code @ServiceConnection}: a aplicação chega a ele pelo {@link #redisProxy}. */
    @Bean
    GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE)
                .withCommand("redis-server", "--save", "", "--appendonly", "no")
                .withExposedPorts(6379)
                .withNetwork(NETWORK)
                .withNetworkAliases(REDIS_ALIAS);
    }

    @Bean
    ToxiproxyContainer toxiproxy() {
        return new ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(NETWORK);
    }

    /** O parâmetro {@code redis} garante que o Redis suba antes do proxy que aponta para ele. */
    @Bean
    RedisProxy redisProxy(ToxiproxyContainer toxiproxy, GenericContainer<?> redis) {
        return RedisProxy.create(toxiproxy, REDIS_ALIAS + ":6379");
    }

    @Bean
    DataRedisConnectionDetails redisConnectionDetails(RedisProxy proxy) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(proxy.host(), proxy.port());
            }
        };
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(KAFKA_IMAGE);
    }
}
