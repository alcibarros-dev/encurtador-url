package br.com.encurtador.identity.api;

import br.com.encurtador.identity.domain.EmailAddress;
import br.com.encurtador.identity.domain.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** Sem regras de formato na senha: qualquer senha que não confira recebe o mesmo 401. */
record LoginRequest(
        @NotBlank @Size(max = EmailAddress.MAX_LENGTH) String email,
        @NotEmpty String password) {

    LoginRequest {
        email = EmailAddress.normalize(email);
        password = PasswordPolicy.normalize(password);
    }
}
