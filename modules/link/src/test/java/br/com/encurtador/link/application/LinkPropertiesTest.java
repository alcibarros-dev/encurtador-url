package br.com.encurtador.link.application;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A configuração inválida falha no startup, não na primeira criação de link. */
class LinkPropertiesTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private final Validator validator = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void defaultsOfTheAdrAreValid() {
        assertThat(validator.validate(new LinkProperties(7, Duration.ofDays(30), Duration.ofDays(365), 100, List.of())))
                .isEmpty();
    }

    @Test
    void defaultTtlCannotExceedMaxTtl() {
        assertThat(validator.validate(new LinkProperties(7, Duration.ofDays(400), Duration.ofDays(365), 100, List.of())))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("default-ttl não pode ser maior que max-ttl");
    }

    @Test
    void codesShorterThanSixCharactersAreRejected() {
        assertThat(validator.validate(new LinkProperties(5, Duration.ofDays(30), Duration.ofDays(365), 100, List.of())))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("codeLength");
    }

    @Test
    void serviceHostsComeFromTheAbsoluteServiceUrls() {
        var properties = new LinkProperties(7, Duration.ofDays(30), Duration.ofDays(365), 100,
                List.of(URI.create("https://auth.exemplo.net"), URI.create("http://localhost:8090"), URI.create("/x")));

        assertThat(properties.serviceHosts()).containsExactly("auth.exemplo.net", "localhost");
    }
}
