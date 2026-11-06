package br.com.encurtador.auth.web;

import br.com.encurtador.auth.credential.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;

/** Formulário de troca de senha, com as duas senhas normalizadas em NFC ao receber os valores. */
public class PasswordChangeForm {

    @NotBlank(message = "Informe a senha atual.")
    private String currentPassword = "";

    @ValidPassword
    private String newPassword = "";

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = PasswordPolicy.normalize(currentPassword);
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = PasswordPolicy.normalize(newPassword);
    }
}
