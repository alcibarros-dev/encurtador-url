package br.com.encurtador.auth.web;

import br.com.encurtador.auth.credential.EmailAddress;
import br.com.encurtador.auth.credential.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Formulário de cadastro, normalizado ao receber os valores: e-mail sem espaços e em minúsculas, senha em NFC
 * (ADR-0001, D9). É uma classe com getters, e não um record, porque o Thymeleaf e o {@code BindingResult} leem os
 * campos como propriedades de JavaBean.
 */
public class RegistrationForm {

    @NotBlank(message = "Informe o e-mail.")
    @Email(message = "Informe um e-mail válido.")
    @Size(max = EmailAddress.MAX_LENGTH, message = "O e-mail pode ter no máximo {max} caracteres.")
    private String email = "";

    @ValidPassword
    private String password = "";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = EmailAddress.normalize(email);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = PasswordPolicy.normalize(password);
    }
}
