package br.com.encurtador.analytics.infra;

import br.com.encurtador.analytics.application.AnalyticsProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Tópicos e tratamento de erro do consumidor de cliques (ADR-0001, D8). */
@Configuration(proxyBeanMethods = false)
class ClickKafkaConfig {

    /** Criado pelo KafkaAdmin no startup, se o broker responder; sem broker, a aplicação sobe assim mesmo. */
    @Bean
    NewTopic linkClicksTopic(AnalyticsProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /** Mesmo número de partições: o recoverer publica na partição do registro original. */
    @Bean
    NewTopic linkClicksDeadLetterTopic(AnalyticsProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /**
     * Duas novas tentativas com 1 s de intervalo e depois a DLT, com destino explícito (sem depender do sufixo padrão
     * da versão do Spring Kafka). Mensagem ilegível e violação de integridade (link inexistente) não melhoram com
     * retry e vão direto.
     */
    @Bean
    DefaultErrorHandler clickErrorHandler(KafkaTemplate<String, String> template, AnalyticsProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(properties.deadLetterTopic(), record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 2));
        handler.addNotRetryableExceptions(InvalidClickEventException.class, DataIntegrityViolationException.class);
        return handler;
    }
}
