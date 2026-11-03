package br.com.encurtador.shared.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00.123Z");

    @Test
    void producesVersion7WithRfcVariant() {
        UUID uuid = generatorAt(NOW).next();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void embedsClockMillisecondsInTheFirst48Bits() {
        UUID uuid = generatorAt(NOW).next();

        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void laterInstantsSortAfterEarlierOnes() {
        UUID earlier = generatorAt(NOW).next();
        UUID later = generatorAt(NOW.plusMillis(1)).next();

        // PostgreSQL compara uuid byte a byte, o que equivale à ordem da forma textual.
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void generatesDistinctValuesWithinTheSameMillisecond() {
        UuidV7Generator generator = generatorAt(NOW);
        Set<UUID> generated = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            generated.add(generator.next());
        }

        assertThat(generated).hasSize(10_000);
    }

    private static UuidV7Generator generatorAt(Instant instant) {
        return new UuidV7Generator(Clock.fixed(instant, ZoneOffset.UTC));
    }
}
