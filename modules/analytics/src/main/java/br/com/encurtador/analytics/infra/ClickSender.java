package br.com.encurtador.analytics.infra;

import br.com.encurtador.analytics.LinkClicked;
import br.com.encurtador.analytics.application.AnalyticsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Esvazia a fila de cliques e publica no Kafka, numa thread dedicada (ADR-0001, D8). Os envios são assíncronos e
 * nunca esperados com {@code get()}; falhas são contadas e não há retry além do produtor (idempotente, com
 * {@code delivery.timeout.ms} de 30 s).
 */
@Component
class ClickSender implements SmartLifecycle {

    /**
     * Abaixo da fase do servidor web: para depois dele, para publicar os cliques das últimas requisições, e antes do
     * produtor do Kafka, que fica na menor fase.
     */
    static final int PHASE = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024;

    private static final int BATCH_SIZE = 500;
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);
    private static final Duration SHUTDOWN_DEADLINE = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(ClickSender.class);

    private final BufferedKafkaClickRecorder buffer;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String topic;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;
    private final AtomicBoolean failing = new AtomicBoolean();

    private volatile boolean running;
    private volatile @Nullable Instant lastSuccessAt;
    private volatile @Nullable Instant lastFailureAt;
    private @Nullable Thread thread;

    ClickSender(BufferedKafkaClickRecorder buffer, KafkaTemplate<String, String> kafka, JsonMapper json,
                AnalyticsProperties properties, MeterRegistry meters, Clock clock) {
        this.buffer = buffer;
        this.kafka = kafka;
        this.json = json;
        this.topic = properties.topic();
        this.clock = clock;
        this.published = meters.counter("shortener.clicks.published");
        this.failed = meters.counter("shortener.clicks.publish.failed");
    }

    @Override
    public synchronized void start() {
        running = true;
        thread = Thread.ofPlatform().name("click-sender").daemon().start(this::run);
    }

    /** Espera o esvaziamento da fila, limitado a {@link #SHUTDOWN_DEADLINE}. */
    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(SHUTDOWN_DEADLINE.plusSeconds(1));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** Para o health indicator: o Kafka não tem um no Spring Boot (D13). */
    PublishStatus status() {
        return new PublishStatus(failing.get(), lastSuccessAt, lastFailureAt);
    }

    private void run() {
        List<LinkClicked> batch = new ArrayList<>(BATCH_SIZE);
        try {
            while (running) {
                if (buffer.take(batch, BATCH_SIZE, POLL_TIMEOUT) > 0) {
                    publish(batch);
                    batch.clear();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        drainBeforeShutdown(batch);
    }

    private void drainBeforeShutdown(List<LinkClicked> batch) {
        long deadline = System.nanoTime() + SHUTDOWN_DEADLINE.toNanos();
        while (System.nanoTime() < deadline && buffer.drainTo(batch, BATCH_SIZE) > 0) {
            publish(batch);
            batch.clear();
        }
        int lost = buffer.pending();
        if (lost > 0) {
            log.warn("{} cliques não publicados no desligamento: prazo de {} s esgotado", lost,
                    SHUTDOWN_DEADLINE.toSeconds());
        }
    }

    private void publish(List<LinkClicked> batch) {
        for (LinkClicked click : batch) {
            try {
                kafka.send(topic, Long.toString(click.linkId()), json.writeValueAsString(click))
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                succeeded();
                            } else {
                                failed(ex);
                            }
                        });
            } catch (RuntimeException ex) {
                // Sem metadados do tópico, o send() espera até max.block.ms (2 s) e lança; só esta thread espera.
                failed(ex);
            }
        }
    }

    private void succeeded() {
        published.increment();
        lastSuccessAt = clock.instant();
        if (failing.compareAndSet(true, false)) {
            log.info("Publicação de cliques no Kafka restabelecida");
        }
    }

    /** Só a primeira falha de uma sequência vai para o log; as demais ficam na métrica. */
    private void failed(Throwable ex) {
        failed.increment();
        lastFailureAt = clock.instant();
        if (failing.compareAndSet(false, true)) {
            log.warn("Falha ao publicar cliques no Kafka: {}", ex.toString());
        }
    }

    record PublishStatus(boolean failing, @Nullable Instant lastSuccessAt, @Nullable Instant lastFailureAt) {
    }
}
