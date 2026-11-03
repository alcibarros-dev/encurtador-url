package br.com.encurtador.identity.api;

import br.com.encurtador.identity.application.PasswordService;
import br.com.encurtador.security.CurrentUser;
import br.com.encurtador.security.OpenApiSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Conta do usuário autenticado (Bearer JWT). */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Conta")
@SecurityRequirement(name = OpenApiSecurity.BEARER)
class UserController {

    private final PasswordService passwords;

    UserController(PasswordService passwords) {
        this.passwords = passwords;
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Troca a senha", description = "Exige a senha atual e encerra todas as sessões. O access "
            + "token em uso continua válido até expirar.")
    @ApiResponse(responseCode = "204", description = "Senha trocada")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED` ou `INVALID_TOKEN`")
    @ApiResponse(responseCode = "422", description = "`WRONG_CURRENT_PASSWORD`")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: limite por usuário")
    void changePassword(@CurrentUser long userId, @Valid @RequestBody ChangePasswordRequest request) {
        passwords.change(userId, request.currentPassword(), request.newPassword());
    }
}
