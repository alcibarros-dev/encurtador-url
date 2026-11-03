package br.com.encurtador.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

/**
 * Validador de {@link MaxUtf8Bytes}. {@code null} é válido; a obrigatoriedade fica com {@code @NotNull}.
 */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

    private int maxBytes;

    @Override
    public void initialize(MaxUtf8Bytes constraint) {
        this.maxBytes = constraint.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || utf8Length(value) <= maxBytes;
    }

    /** Mesma contagem que o encoder faz ({@code getBytes(UTF_8)}), inclusive para surrogates isolados. */
    public static int utf8Length(CharSequence value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
