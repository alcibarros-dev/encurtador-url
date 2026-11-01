package br.com.encurtador.analytics.infra;

import br.com.encurtador.analytics.LinkClicked;
import br.com.encurtador.analytics.application.AnalyticsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Fila, envio assíncrono e desligamento, com um KafkaTemplate falso (ADR-0001, D8 e D13). */
class ClickSenderTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AnalyticsProperties properties = new AnalyticsProperties(3, "link-clicks", "link-clicks-dlt", 3, 1,
            ZoneId.of("America/Sao_Paulo"));
    private final BufferedKafkaClickRecorder buffer = new BufferedKafkaClickRecorder(properties, meters);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ClickSender sender = new ClickSender(buffer, kafka, JsonMapper.builder().build(), properties, meters,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final KafkaPublishHealthIndicator health = new KafkaPublishHealthIndicator(sender);

    @AfterEach
    void stop() {
        sender.stop();
    }

    @Test
    void publishesEachClickAsJsonKeyedByLinkId() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        UUID eventId = UUID.randomUUID();
        buffer.offer(new LinkClicked(1, eventId, 42, NOW, "t.co"));

        sender.start();

        verify(kafka, timeout(2000)).send(eq("link-clicks"), eq("42"), contains("\"eventId\":\"" + eventId + "\""));
        await().until(() -> counter("shortener.clicks.published") == 1);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(health.health().getDetails()).containsEntry("lastSuccessAt", NOW);
    }

    @Test
    void fullQueueDropsTheClickWithoutBlocking() {
        for (int i = 0; i < 5; i++) {
            buffer.offer(click());
        }

        assertThat(counter("shortener.clicks.enqueued")).isEqualTo(3);
        assertThat(counter("shortener.clicks.dropped")).isEqualTo(2);
    }

    @Test
    void failedDeliveryIsCountedAndReportedUntilTheNextSuccess() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker fora")))
                .thenReturn(CompletableFuture.completedFuture(null));
        sender.start();

        buffer.offer(click());
        await().until(() -> counter("shortener.clicks.publish.failed") == 1);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("lastFailureAt", NOW);

        buffer.offer(click());
        await().until(() -> counter("shortener.clicks.published") == 1);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void sendThatThrowsIsCountedAsAFailure() {
        // É o que acontece sem metadados do tópico, depois de max.block.ms.
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new KafkaException("sem metadados"));
        buffer.offer(click());

        sender.start();

        await().until(() -> counter("shortener.clicks.publish.failed") == 1);
        assertThat(sender.status().failing()).isTrue();
    }

    @Test
    void stoppingPublishesWhatIsStillQueued() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        buffer.offer(click());
        buffer.offer(click());
        buffer.offer(click());

        sender.start();
        sender.stop();

        verify(kafka, times(3)).send(anyString(), anyString(), anyString());
        assertThat(buffer.pending()).isZero();
        assertThat(sender.isRunning()).isFalse();
    }

    @Test
    void stopsAfterTheWebServer() {
        assertThat(sender.getPhase()).isLessThan(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE);
    }

    private double counter(String name) {
        return meters.counter(name).count();
    }

    private static LinkClicked click() {
        return new LinkClicked(1, UUID.randomUUID(), 42, NOW, null);
    }
}
