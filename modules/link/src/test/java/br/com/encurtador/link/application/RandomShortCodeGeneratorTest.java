package br.com.encurtador.link.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RandomShortCodeGeneratorTest {

    private static final int SAMPLES = 10_000;

    private final RandomShortCodeGenerator generator = new RandomShortCodeGenerator(
            new LinkProperties(7, Duration.ofDays(30), Duration.ofDays(365), 100, List.of()));

    @Test
    void generatesBase62CodesOfTheConfiguredLength() {
        Set<String> codes = new HashSet<>();
        Set<Character> alphabet = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            String code = generator.next();
            assertThat(code).matches("[0-9A-Za-z]{7}");
            codes.add(code);
            code.chars().forEach(c -> alphabet.add((char) c));
        }

        // Com 62^7 códigos possíveis, uma repetição em 10 mil amostras tem chance da ordem de 10^-5.
        assertThat(codes).hasSize(SAMPLES);
        assertThat(alphabet).hasSize(62);
    }
}
