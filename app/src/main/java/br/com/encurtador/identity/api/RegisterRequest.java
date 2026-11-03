package br.com.encurtador.identity.api;

import br.com.encurtador.identity.domain.EmailAddress;
import br.com.encurtador.identity.domain.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A normalização acontece no construtor, antes da validação. O e-mail precisa ser ASCII: o {@code lower()} do
 * PostgreSQL e o de Java divergem em letras fora do ASCII, e o check {@code email = lower(email)} recusaria a linha.
 */
record RegisterRequest(
        @NotBlank
        @Size(max = EmailAddress.MAX_LENGTH)
        @Email
        @Pattern(regexp = "\\p{ASCII}*", message = "deve conter apenas caracteres ASCII")
        String email,
        @ValidPassword
        String password) {

    RegisterRequest {
        email = EmailAddress.normalize(email);
        password = PasswordPolicy.normalize(password);
    }
}
