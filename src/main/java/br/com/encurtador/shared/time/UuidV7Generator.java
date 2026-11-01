package br.com.encurtador.shared.time;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Gera UUIDs versão 7 (RFC 9562): 48 bits de timestamp Unix em milissegundos seguidos de bits aleatórios.
 * <p>
 * Ordenados no tempo, evitam inserções aleatórias no índice de chave primária (ADR-0001, D6).
 */
@Component
public class UuidV7Generator {

    private static final long VERSION_7 = 0x7L << 12;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UuidV7Generator(Clock clock) {
        this.clock = clock;
    }

    public UUID next() {
        long timestamp = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long randA = random.nextInt(1 << 12);
        long mostSignificant = (timestamp << 16) | VERSION_7 | randA;
        long leastSignificant = (random.nextLong() & VARIANT_MASK) | VARIANT_RFC_9562;
        return new UUID(mostSignificant, leastSignificant);
    }
}
