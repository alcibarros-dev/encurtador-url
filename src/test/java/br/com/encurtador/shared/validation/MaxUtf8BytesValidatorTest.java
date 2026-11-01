package br.com.encurtador.shared.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaxUtf8BytesValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    record Sample(@MaxUtf8Bytes(72) String value) {
    }

    @ParameterizedTest(name = "\"{0}\" × {1} = {2} bytes")
    @CsvSource({
            "a, 72, true",   // ASCII: 1 byte por caractere
            "a, 73, false",
            "ç, 36, true",   // 2 bytes
            "ç, 37, false",
            "€, 24, true",   // 3 bytes
            "€, 25, false",
            "😀, 18, true",  // 4 bytes (par de surrogates)
            "😀, 19, false"})
    void countsBytesNotCharacters(String unit, int repetitions, boolean valid) {
        assertThat(validator.validate(new Sample(unit.repeat(repetitions))).isEmpty()).isEqualTo(valid);
    }

    @Test
    void nullIsLeftForNotNull() {
        assertThat(validator.validate(new Sample(null))).isEmpty();
    }

    @Test
    void messageStatesTheLimit() {
        Set<ConstraintViolation<Sample>> violations = validator.validate(new Sample("a".repeat(73)));

        assertThat(violations).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("deve ter no máximo 72 bytes em UTF-8");
    }
}
