package br.com.encurtador.identity;

import br.com.encurtador.identity.application.AccessTokenIssuer;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fluxos de conta e sessão pela API real (ADR-0001, D9 e D12; cenários 8 e 18 do D14).
 */
@IntegrationTest
class IdentityIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String CHANGE_PASSWORD = "/api/v1/users/me/password";

    private static final String PASSWORD = "senha-segura-1";
    private static final String NEW_PASSWORD = "nova-senha-segura";
    /** 40 caracteres, dentro do limite de 64, mas 74 bytes em UTF-8. */
    private static final String PASSWORD_OVER_72_BYTES = "senha-" + "😀".repeat(17);

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    AccessTokenIssuer tokenIssuer;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- cadastro ----

    @Test
    void registrationStoresNormalizedEmailAndBcryptHash() {
        String email = newEmail();

        Response response = api.post(REGISTER,
                Map.of("email", "  " + email.toUpperCase(Locale.ROOT) + " ", "password", PASSWORD));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.text("email")).isEqualTo(email);
        assertThat(response.body().path("id").asLong()).isPositive();
        assertThat(response.body().has("password")).isFalse();
        assertThat(response.body().has("passwordHash")).isFalse();

        var stored = jdbc.sql("select password_hash, created_at from users where email = :email")
                .param("email", email)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .single();
        assertThat(stored.getKey()).startsWith("{bcrypt}");
        assertThat(Instant.parse(response.text("createdAt"))).isEqualTo(stored.getValue());
    }

    @Test
    void duplicateEmailIsRejectedRegardlessOfCase() {
        String email = newEmail();
        register(email, PASSWORD);

        Response duplicate = api.post(REGISTER, Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD));

        assertProblem(duplicate, 409, "EMAIL_TAKEN");
    }

    @Test
    void invalidRegistrationListsEveryFieldInPortuguese() {
        Response response = api.post(REGISTER, Map.of("email", "nao-e-um-email", "password", "curta"));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(errorFields(response)).containsExactlyInAnyOrder("email", "password");
        assertThat(errorMessages(response))
                .contains("deve ser um endereço de e-mail bem formado", "tamanho deve ser entre 8 e 64");
    }

    @Test
    void passwordOver72Utf8BytesIsRejectedWith400NotWith500() {
        Response response = api.post(REGISTER, Map.of("email", newEmail(), "password", PASSWORD_OVER_72_BYTES));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(errorFields(response)).containsExactly("password");
        assertThat(errorMessages(response)).containsExactly("deve ter no máximo 72 bytes em UTF-8");
    }

    @Test
    void nonAsciiEmailIsRejected() {
        Response response = api.post(REGISTER,
                Map.of("email", "joão." + UUID.randomUUID() + "@exemplo.com", "password", PASSWORD));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(errorFields(response)).containsOnly("email");
    }

    @Test
    void malformedBodyIsRejectedWith400() {
        assertProblem(api.postRaw(REGISTER, "{\"email\":"), 400, "MALFORMED_REQUEST");
    }

    // ---- login ----

    @Test
    void loginReturnsTokenPairWhoseAccessTokenAuthenticates() {
        String email = newEmail();
        long userId = register(email, PASSWORD);

        Response login = login(email, PASSWORD);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("tokenType")).isEqualTo("Bearer");
        assertThat(login.body().path("expiresIn").asLong()).isEqualTo(900);
        assertThat(login.text("refreshToken")).hasSize(43);
        assertThat(login.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(jwtDecoder.decode(login.text("accessToken")).getSubject()).isEqualTo(Long.toString(userId));

        // Autenticado, o pedido chega à regra de negócio: senha atual errada.
        Response change = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", "outra-senha-1", "newPassword", NEW_PASSWORD), login.text("accessToken"));
        assertProblem(change, 422, "WRONG_CURRENT_PASSWORD");
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() {
        String email = newEmail();
        register(email, PASSWORD);

        Response wrongPassword = login(email, "senha-errada-1");
        Response unknownEmail = login(newEmail(), PASSWORD);
        Response over72Bytes = login(email, PASSWORD_OVER_72_BYTES);

        assertProblem(wrongPassword, 401, "INVALID_CREDENTIALS");
        assertProblem(unknownEmail, 401, "INVALID_CREDENTIALS");
        assertProblem(over72Bytes, 401, "INVALID_CREDENTIALS");
        assertThat(unknownEmail.text("detail")).isEqualTo(wrongPassword.text("detail"));
        assertThat(over72Bytes.text("detail")).isEqualTo(wrongPassword.text("detail"));
    }

    @Test
    void equivalentUnicodeFormsAreTheSamePassword() {
        String email = newEmail();
        register(email, "café-com-leite"); // NFD: "e" + acento agudo combinante

        assertThat(login(email, "café-com-leite").status()).isEqualTo(200); // NFC
    }

    // ---- refresh e logout ----

    @Test
    void refreshRotatesTheRefreshToken() {
        String first = loginNewUser().text("refreshToken");

        Response second = refresh(first);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.text("accessToken")).isNotBlank();
        assertThat(second.text("refreshToken")).isNotEqualTo(first);
        assertThat(refresh(second.text("refreshToken")).status()).isEqualTo(200);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSession() {
        String first = loginNewUser().text("refreshToken");
        String second = refresh(first).text("refreshToken");

        assertProblem(refresh(first), 401, "INVALID_REFRESH_TOKEN");
        assertProblem(refresh(second), 401, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        String token = loginNewUser().text("refreshToken");

        clock.advance(Duration.ofDays(7).plusSeconds(1));

        assertProblem(refresh(token), 401, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void renewalsNeverExtendTheSessionBeyondItsAbsoluteLimit() {
        String token = loginNewUser().text("refreshToken");
        for (int renewal = 1; renewal <= 4; renewal++) { // dias 6, 12, 18 e 24
            clock.advance(Duration.ofDays(6));
            Response renewed = refresh(token);
            assertThat(renewed.status()).isEqualTo(200);
            token = renewed.text("refreshToken");
        }

        // Emitido no dia 24, valeria 7 dias (até o dia 31); a sessão acaba no dia 30.
        clock.advance(Duration.ofDays(6).plusMinutes(1));

        assertProblem(refresh(token), 401, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void logoutEndsTheSessionAndIsIdempotent() {
        String token = loginNewUser().text("refreshToken");

        assertThat(api.post(LOGOUT, Map.of("refreshToken", token)).status()).isEqualTo(204);
        assertProblem(refresh(token), 401, "INVALID_REFRESH_TOKEN");
        assertThat(api.post(LOGOUT, Map.of("refreshToken", token)).status()).isEqualTo(204);
        assertThat(api.post(LOGOUT, Map.of("refreshToken", "token-desconhecido")).status()).isEqualTo(204);
    }

    @Test
    void blankRefreshTokenIsAValidationError() {
        assertProblem(api.post(REFRESH, Map.of("refreshToken", " ")), 400, "VALIDATION_FAILED");
    }

    // ---- troca de senha ----

    @Test
    void changingPasswordRequiresTheCurrentOneAndEndsEverySession() {
        String email = newEmail();
        register(email, PASSWORD);
        Response sessionA = login(email, PASSWORD);
        Response sessionB = login(email, PASSWORD);
        String accessToken = sessionA.text("accessToken");

        Response wrong = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", "senha-errada-1", "newPassword", NEW_PASSWORD), accessToken);
        Response changed = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), accessToken);

        assertProblem(wrong, 422, "WRONG_CURRENT_PASSWORD");
        assertThat(changed.status()).isEqualTo(204);
        assertProblem(refresh(sessionA.text("refreshToken")), 401, "INVALID_REFRESH_TOKEN");
        assertProblem(refresh(sessionB.text("refreshToken")), 401, "INVALID_REFRESH_TOKEN");
        assertProblem(login(email, PASSWORD), 401, "INVALID_CREDENTIALS");
        assertThat(login(email, NEW_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void currentPasswordOver72BytesIsJustAWrongPassword() {
        String accessToken = loginNewUser().text("accessToken");

        Response response = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", PASSWORD_OVER_72_BYTES, "newPassword", NEW_PASSWORD), accessToken);

        assertProblem(response, 422, "WRONG_CURRENT_PASSWORD");
    }

    @Test
    void newPasswordFollowsThePolicy() {
        String accessToken = loginNewUser().text("accessToken");

        Response response = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD_OVER_72_BYTES), accessToken);

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(errorFields(response)).containsExactly("newPassword");
    }

    @Test
    void changingPasswordRequiresBearerToken() {
        Response response = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), null);

        assertProblem(response, 401, "UNAUTHENTICATED");
        assertThat(response.header("WWW-Authenticate")).hasValueSatisfying(value -> assertThat(value).startsWith("Bearer"));
    }

    @Test
    void tokenOfAnAccountThatDoesNotExistIsRejected() {
        String token = tokenIssuer.issue(Long.MAX_VALUE).value();

        Response response = api.put(CHANGE_PASSWORD,
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), token);

        assertProblem(response, 401, "INVALID_TOKEN");
    }

    // ---- apoio ----

    private long register(String email, String password) {
        Response response = api.post(REGISTER, Map.of("email", email, "password", password));
        assertThat(response.status()).isEqualTo(201);
        return response.body().path("id").asLong();
    }

    private Response login(String email, String password) {
        return api.post(LOGIN, Map.of("email", email, "password", password));
    }

    private Response loginNewUser() {
        String email = newEmail();
        register(email, PASSWORD);
        Response login = login(email, PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        return login;
    }

    private Response refresh(String refreshToken) {
        return api.post(REFRESH, Map.of("refreshToken", refreshToken));
    }

    private static String newEmail() {
        return "usuario-" + UUID.randomUUID() + "@exemplo.com";
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo(code);
    }

    private static List<String> errorFields(Response response) {
        return errors(response, "field");
    }

    private static List<String> errorMessages(Response response) {
        return errors(response, "message");
    }

    private static List<String> errors(Response response, String property) {
        List<String> values = new ArrayList<>();
        for (JsonNode error : response.body().path("errors")) {
            values.add(error.path(property).asString());
        }
        return values;
    }
}
