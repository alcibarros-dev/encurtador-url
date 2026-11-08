package br.com.encurtador.redirect.infra;

import br.com.encurtador.clicks.LinkClicked;
import br.com.encurtador.redirect.application.ClickProperties;
import br.com.encurtador.redirect.application.ClickRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fila limitada entre o redirecionamento e o Kafka (ADR-0001, D8; corrige P2). Quem esvazia é o {@link ClickSender},
 * numa thread própria.
 * <p>
 * É a principal janela de perda: num crash, o que estiver aqui some. Aceito, porque analytics é best-effort.
 */
@Component
class BufferedKafkaClickRecorder implements ClickRecorder {

    private final BlockingQueue<LinkClicked> queue;
    private final Counter enqueued;
    private final Counter dropped;

    BufferedKafkaClickRecorder(ClickProperties properties, MeterRegistry meters) {
        this.queue = new ArrayBlockingQueue<>(properties.bufferCapacity());
        this.enqueued = meters.counter("shortener.clicks.enqueued");
        this.dropped = meters.counter("shortener.clicks.dropped");
    }

    @Override
    public void offer(LinkClicked click) {
        if (queue.offer(click)) {
            enqueued.increment();
        } else {
            dropped.increment();
        }
    }

    /**
     * Espera até {@code timeout} pelo primeiro clique e leva junto os que já estiverem na fila.
     *
     * @return quantos cliques foram para {@code target}
     */
    int take(Collection<LinkClicked> target, int max, Duration timeout) throws InterruptedException {
        LinkClicked first = queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (first == null) {
            return 0;
        }
        target.add(first);
        return 1 + queue.drainTo(target, max - 1);
    }

    /** Sem esperar: usado no desligamento. */
    int drainTo(Collection<LinkClicked> target, int max) {
        return queue.drainTo(target, max);
    }

    int pending() {
        return queue.size();
    }
}
