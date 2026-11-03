package br.com.encurtador.identity.api;

import br.com.encurtador.identity.domain.PasswordPolicy;
import jakarta.validation.constraints.NotEmpty;

record ChangePasswordRequest(@NotEmpty String currentPassword, @ValidPassword String newPassword) {

    ChangePasswordRequest {
        currentPassword = PasswordPolicy.normalize(currentPassword);
        newPassword = PasswordPolicy.normalize(newPassword);
    }

    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
