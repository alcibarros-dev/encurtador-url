package br.com.encurtador.link.application;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
        assertThat(validator.validate(new LinkProperties(7, Duration.ofDays(30), Duration.ofDays(365), 100))).isEmpty();
    }

    @Test
    void defaultTtlCannotExceedMaxTtl() {
        assertThat(validator.validate(new LinkProperties(7, Duration.ofDays(400), Duration.ofDays(365), 100)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("default-ttl não pode ser maior que max-ttl");
    }

    @Test
    void codesShorterThanSixCharactersAreRejected() {
        assertThat(validator.validate(new LinkProperties(5, Duration.ofDays(30), Duration.ofDays(365), 100)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("codeLength");
    }
}
