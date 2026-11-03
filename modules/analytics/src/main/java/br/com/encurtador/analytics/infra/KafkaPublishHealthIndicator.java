package br.com.encurtador.analytics.infra;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Saúde da publicação de cliques, pelo último envio do {@link ClickSender} (ADR-0001, D13). Aparece em
 * {@code /actuator/health} como {@code kafkaPublish}, mas fica fora da readiness: o redirecionamento não depende do
 * Kafka (RNF02).
 */
@Component
class KafkaPublishHealthIndicator implements HealthIndicator {

    private final ClickSender sender;

    KafkaPublishHealthIndicator(ClickSender sender) {
        this.sender = sender;
    }

    @Override
    public Health health() {
        ClickSender.PublishStatus status = sender.status();
        Health.Builder health = status.failing() ? Health.down() : Health.up();
        if (status.lastSuccessAt() != null) {
            health.withDetail("lastSuccessAt", status.lastSuccessAt());
        }
        if (status.lastFailureAt() != null) {
            health.withDetail("lastFailureAt", status.lastFailureAt());
        }
        return health.build();
    }
}
