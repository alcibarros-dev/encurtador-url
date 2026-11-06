package br.com.encurtador.auth.web;

import br.com.encurtador.auth.credential.PasswordPolicy;
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
@NotNull(message = "Informe a senha.")
@Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
        message = "A senha precisa ter de {min} a {max} caracteres.")
@MaxUtf8Bytes(value = PasswordPolicy.MAX_UTF8_BYTES,
        message = "A senha pode ter no máximo {value} bytes; acentos e emojis contam mais de um.")
@interface ValidPassword {

    String message() default "senha inválida";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
