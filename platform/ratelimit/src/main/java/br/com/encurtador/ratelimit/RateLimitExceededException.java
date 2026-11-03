package br.com.encurtador.ratelimit;

import br.com.encurtador.shared.error.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/** 429 com {@code Retry-After}, no mesmo formato no filtro e no handler global (ADR-0001, D11 e D12). */
public class RateLimitExceededException extends ApiException {

    public static final String RATE_LIMITED = "RATE_LIMITED";

    private final long retryAfterSeconds;

    public RateLimitExceededException(Duration retryAfter) {
        this(Math.max(1, (retryAfter.toMillis() + 999) / 1000));
    }

    private RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED,
                "Muitas requisições. Tente novamente em " + retryAfterSeconds + " segundos.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Arredondado para cima e nunca zero: o cliente não volta antes de a janela virar. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
