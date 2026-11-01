package br.com.encurtador.shared.redis;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Disjuntor compartilhado por quem usa o Redis como acessório: cache e rate limit (ADR-0001, D7 e D11). O Redis
 * nunca derruba uma requisição; no pior caso, custa um timeout.
 * <p>
 * Depois de {@code failureThreshold} falhas seguidas, o Redis é ignorado por {@code openDuration}. Passado esse tempo,
 * uma única chamada de teste decide: sucesso fecha o disjuntor, falha o reabre.
 */
@Component
public class RedisGuard {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** Resultado de uma chamada protegida. */
    public sealed interface Result<T> {

        record Done<T>(@Nullable T value) implements Result<T> {
        }

        record Failed<T>(RuntimeException cause) implements Result<T> {
        }

        /** O disjuntor estava aberto e o Redis nem foi chamado. */
        record Bypassed<T>() implements Result<T> {
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;

    private volatile State state = State.CLOSED;
    private volatile int consecutiveFailures;
    private long openedAt;
    private boolean trialInFlight;

    @Autowired
    public RedisGuard(RedisGuardProperties properties, MeterRegistry meters) {
        this(properties, meters, System::nanoTime);
    }

    /** @param nanoTime relógio monotônico; os testes unitários passam um falso */
    RedisGuard(RedisGuardProperties properties, MeterRegistry meters, LongSupplier nanoTime) {
        this.failureThreshold = properties.failureThreshold();
        this.openNanos = properties.openDuration().toNanos();
        this.nanoTime = nanoTime;
        for (State gaugeState : State.values()) {
            Gauge.builder("shortener.redis.circuit", this, guard -> guard.state == gaugeState ? 1 : 0)
                    .tag("state", gaugeState.name().toLowerCase(Locale.ROOT))
                    .register(meters);
        }
    }

    public State state() {
        return state;
    }

    /** Qualquer exceção da operação conta como falha do Redis e nunca é propagada. */
    public <T> Result<T> call(Supplier<T> operation) {
        if (!tryAcquire()) {
            return new Result.Bypassed<>();
        }
        try {
            T value = operation.get();
            onSuccess();
            return new Result.Done<>(value);
        } catch (RuntimeException ex) {
            onFailure(ex);
            return new Result.Failed<>(ex);
        }
    }

    private boolean tryAcquire() {
        if (state == State.CLOSED) {
            return true; // caminho quente, sem lock
        }
        synchronized (this) {
            if (state == State.OPEN && nanoTime.getAsLong() - openedAt >= openNanos) {
                state = State.HALF_OPEN;
                trialInFlight = false;
            }
            if (state == State.HALF_OPEN && !trialInFlight) {
                trialInFlight = true;
                return true;
            }
            return state == State.CLOSED;
        }
    }

    private void onSuccess() {
        if (state == State.CLOSED && consecutiveFailures == 0) {
            return; // caminho quente, sem lock
        }
        synchronized (this) {
            consecutiveFailures = 0;
            if (state != State.CLOSED) {
                state = State.CLOSED;
                log.info("Redis voltou a responder: disjuntor fechado");
            }
        }
    }

    private synchronized void onFailure(RuntimeException ex) {
        if (state == State.HALF_OPEN) {
            open(ex);
        } else if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
            open(ex);
        }
        // Aberto: uma chamada liberada antes da abertura terminou depois dela; nada a fazer.
    }

    private void open(RuntimeException ex) {
        state = State.OPEN;
        openedAt = nanoTime.getAsLong();
        trialInFlight = false;
        consecutiveFailures = 0;
        log.warn("Redis indisponível, ignorado por {} ms: {}", openNanos / 1_000_000, ex.toString());
    }
}
