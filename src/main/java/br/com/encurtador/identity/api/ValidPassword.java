package br.com.encurtador.identity.api;

import br.com.encurtador.identity.domain.PasswordPolicy;
import br.com.encurtador.shared.validation.MaxUtf8Bytes;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Senha nova (cadastro e troca), já normalizada para NFC: cada regra violada vira uma entrada em {@code errors[]}.
 */
@Documented
@Constraint(validatedBy = {})
@Target({METHOD, FIELD, ANNOTATION_TYPE, PARAMETER})
@Retention(RUNTIME)
@NotNull
@Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
@MaxUtf8Bytes(PasswordPolicy.MAX_UTF8_BYTES)
@interface ValidPassword {

    String message() default "senha inválida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
