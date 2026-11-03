package br.com.encurtador.identity.api;

import br.com.encurtador.identity.application.AuthService;
import br.com.encurtador.identity.application.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rotas públicas de autenticação (ADR-0001, D9 e D12). */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticação", description = "Rotas públicas. Refresh e logout usam o refresh token no corpo.")
class AuthController {

    private final RegistrationService registration;
    private final AuthService auth;

    AuthController(RegistrationService registration, AuthService auth) {
        this.registration = registration;
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cadastra uma conta",
            description = "E-mail ASCII de até 254 caracteres; senha de 8 a 64 caracteres e até 72 bytes em UTF-8.")
    @ApiResponse(responseCode = "201", description = "Conta criada", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` ou `MALFORMED_REQUEST`")
    @ApiResponse(responseCode = "409", description = "`EMAIL_TAKEN`")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: limite de cadastros por IP")
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(registration.register(request.email(), request.password()));
    }

    @PostMapping("/login")
    @Operation(summary = "Abre uma sessão",
            description = "Devolve um access token de 15 minutos e um refresh token de uso único.")
    @ApiResponse(responseCode = "200", description = "Sessão aberta", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`")
    @ApiResponse(responseCode = "401", description = "`INVALID_CREDENTIALS`, igual para e-mail inexistente e senha "
            + "errada")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: limite por IP e por e-mail")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.from(auth.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renova a sessão",
            description = "Troca o refresh token por um par novo; o antigo deixa de valer. Reapresentar um token já "
                    + "trocado encerra a sessão inteira.")
    @ApiResponse(responseCode = "200", description = "Par novo", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", description = "`INVALID_REFRESH_TOKEN`")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: limite por IP")
    TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return TokenResponse.from(auth.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Encerra a sessão", description = "Idempotente: token desconhecido também responde 204.")
    @ApiResponse(responseCode = "204", description = "Sessão encerrada")
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        auth.logout(request.refreshToken());
    }
}
