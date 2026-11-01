package br.com.encurtador.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Limita o tamanho do texto em bytes UTF-8, não em caracteres. Existe por causa do bcrypt, que recusa senhas acima
 * de 72 bytes: com acentos ou emojis esse limite chega antes do limite de caracteres (ADR-0001, D9).
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "deve ter no máximo {value} bytes em UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
