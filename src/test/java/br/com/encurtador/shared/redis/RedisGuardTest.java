package br.com.encurtador.shared.redis;

import br.com.encurtador.shared.redis.RedisGuard.Result;
import br.com.encurtador.shared.redis.RedisGuard.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Máquina de estados do disjuntor, com relógio falso (ADR-0001, D7). */
class RedisGuardTest {

    private static final Duration OPEN = Duration.ofSeconds(5);

    private final AtomicLong nanos = new AtomicLong();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RedisGuard guard = new RedisGuard(new RedisGuardProperties(3, OPEN), meters, nanos::get);
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void returnsTheValueWhileRedisAnswers() {
        assertThat(guard.call(() -> "valor")).isEqualTo(new Result.Done<>("valor"));
        assertThat(guard.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void failuresAreReturnedNeverThrown() {
        QueryTimeoutException timeout = new QueryTimeoutException("timeout");

        Result<String> result = guard.call(() -> {
            throw timeout;
        });

        assertThat(result).isEqualTo(new Result.Failed<>(timeout));
    }

    @Test
    void opensAfterConsecutiveFailuresAndStopsCallingRedis() {
        fail(3);

        assertThat(guard.state()).isEqualTo(State.OPEN);
        assertThat(guard.call(this::countedCall)).isInstanceOf(Result.Bypassed.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        fail(2);
        guard.call(() -> "ok");
        fail(2);

        assertThat(guard.state()).isEqualTo(State.CLOSED);
    }

    @Test
    void afterTheOpenPeriodASingleTrialDecides() {
        fail(3);
        nanos.addAndGet(OPEN.toNanos() - 1);
        assertThat(guard.call(this::countedCall)).isInstanceOf(Result.Bypassed.class);

        nanos.addAndGet(1);
        Result<Result<String>> trial = guard.call(() -> {
            // Enquanto a chamada de teste não termina, as outras continuam desviadas.
            assertThat(guard.state()).isEqualTo(State.HALF_OPEN);
            return guard.call(this::countedCall);
        });

        assertThat(trial).isEqualTo(new Result.Done<>(new Result.Bypassed<String>()));
        assertThat(guard.state()).isEqualTo(State.CLOSED);
        assertThat(guard.call(this::countedCall)).isEqualTo(new Result.Done<>("ok"));
    }

    @Test
    void aFailedTrialReopensForAnotherPeriod() {
        fail(3);
        nanos.addAndGet(OPEN.toNanos());

        fail(1);

        assertThat(guard.state()).isEqualTo(State.OPEN);
        nanos.addAndGet(OPEN.toNanos() - 1);
        assertThat(guard.call(this::countedCall)).isInstanceOf(Result.Bypassed.class);
    }

    @Test
    void stateIsPublishedAsGauges() {
        fail(3);

        assertThat(meters.get("shortener.redis.circuit").tag("state", "open").gauge().value()).isEqualTo(1);
        assertThat(meters.get("shortener.redis.circuit").tag("state", "closed").gauge().value()).isZero();
        assertThat(meters.get("shortener.redis.circuit").tag("state", "half_open").gauge().value()).isZero();
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            guard.call(() -> {
                throw new QueryTimeoutException("timeout");
            });
        }
    }

    private String countedCall() {
        calls.incrementAndGet();
        return "ok";
    }
}
