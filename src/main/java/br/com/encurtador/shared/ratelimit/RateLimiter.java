package br.com.encurtador.shared.ratelimit;

import br.com.encurtador.shared.ratelimit.RateLimitProperties.Limit;
import br.com.encurtador.shared.redis.RedisGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Janela fixa no Redis: um contador por alvo, chave e janela, com {@code INCR} e {@code PEXPIRE} atômicos num script
 * Lua (ADR-0001, D11). Permite rajadas de até 2× na virada da janela (aceito).
 * <p>
 * Fail-open: com o Redis fora ou o disjuntor aberto, a requisição passa e a métrica
 * {@code shortener.ratelimit.failopen} sobe. O limite protege contra abuso; derrubá-lo junto com o Redis seria pior.
 */
@Component
public class RateLimiter {

    /** Só a primeira requisição da janela define o TTL: a chave some sozinha depois que a janela vira. */
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /** Folga do TTL sobre o fim da janela, para relógios ligeiramente diferentes entre instâncias. */
    private static final long TTL_MARGIN_MILLIS = 1_000;

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final MeterRegistry meters;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, RedisGuard guard, MeterRegistry meters, Clock clock) {
        this.redis = redis;
        this.guard = guard;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param rule    nome do alvo ({@code login}, {@code redirect}…), também usado como tag das métricas
     * @param subject quem é limitado: IP, id do usuário ou e-mail
     */
    public Decision tryAcquire(String rule, Limit limit, String subject) {
        Instant now = clock.instant();
        long windowMillis = limit.window().toMillis();
        long windowEnd = (now.toEpochMilli() / windowMillis + 1) * windowMillis;
        Duration untilNextWindow = Duration.ofMillis(windowEnd - now.toEpochMilli());
        String key = key(rule, subject, limit.window(), now);
        String ttl = Long.toString(untilNextWindow.toMillis() + TTL_MARGIN_MILLIS);

        return switch (guard.call(() -> redis.execute(INCREMENT, List.of(key), ttl))) {
            case RedisGuard.Result.Done<Long>(Long count) when count != null && count > limit.requests() -> {
                meters.counter("shortener.ratelimit.rejected", "rule", rule).increment();
                yield new Decision.Rejected(untilNextWindow);
            }
            case RedisGuard.Result.Done<Long> _ -> new Decision.Allowed();
            case RedisGuard.Result.Failed<Long> _, RedisGuard.Result.Bypassed<Long> _ -> {
                meters.counter("shortener.ratelimit.failopen", "rule", rule).increment();
                yield new Decision.Allowed();
            }
        };
    }

    /**
     * Chave {@code rl:{alvo}:{hash}:{janela}}. O sujeito entra como hash: e-mail e IP são dados pessoais e não
     * precisam ficar legíveis no Redis.
     */
    public static String key(String rule, String subject, Duration window, Instant at) {
        return "rl:" + rule + ":" + hash(subject) + ":" + at.toEpochMilli() / window.toMillis();
    }

    private static String hash(String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 é obrigatório em toda JVM", ex);
        }
    }

    public sealed interface Decision {

        record Allowed() implements Decision {
        }

        /** @param retryAfter tempo até a janela virar */
        record Rejected(Duration retryAfter) implements Decision {
        }
    }
}
