package br.com.encurtador.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Relógio do sistema com deslocamento ajustável, para testar expiração sem esperar. Substitui o {@link Clock} da
 * aplicação em todos os testes de integração; quem o avança precisa chamar {@link #reset()} ao terminar.
 */
public final class MutableClock extends Clock {

    private final Clock base = Clock.systemUTC();
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(base.withZone(zone), offset);
    }
}
