package br.com.encurtador.link;

import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.ScriptedShortCodeGenerator;
import br.com.encurtador.support.TestAccounts;
import br.com.encurtador.support.TestAccounts.TestAccount;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API de links pela aplicação real (ADR-0001, §2.2, §2.3, D3, D5, D10 e D12; cenário 17 do D14).
 */
@IntegrationTest
class LinkIntegrationTest {

    private static final String LINKS = "/api/v1/links";
    private static final String TARGET = "https://exemplo.com/artigo?id=42";

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    ScriptedShortCodeGenerator codes;

    @Autowired
    TestAccounts accounts;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    private ApiClient api;
    private String token;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        token = newUserToken();
    }

    @AfterEach
    void resetDoubles() {
        clock.reset();
        codes.reset();
    }

    // ---- criação ----

    @Test
    void createsLinkWithGeneratedCodeAndDefaultExpiration() {
        double createdBefore = counter("shortener.links.created");

        Response response = create(Map.of("url", "HTTPS://Exemplo.COM/artigo?id=42"));

        assertThat(response.status()).isEqualTo(201);
        String code = response.text("code");
        assertThat(code).matches("[0-9A-Za-z]{7}");
        assertThat(response.header("Location")).hasValue(LINKS + "/" + code);
        // O link curto é do serviço de redirecionamento (ADR-0006, D1).
        assertThat(response.text("shortUrl")).isEqualTo("http://localhost:8060/" + code);
        assertThat(response.text("targetUrl")).isEqualTo(TARGET);
        assertThat(response.text("status")).isEqualTo("ACTIVE");
        Instant createdAt = Instant.parse(response.text("createdAt"));
        assertThat(Instant.parse(response.text("expiresAt"))).isEqualTo(createdAt.plus(Duration.ofDays(30)));
        assertThat(counter("shortener.links.created")).isEqualTo(createdBefore + 1);

        Map<String, Object> row = jdbc.sql("select custom_alias, status, target_url from links where code = :code")
                .param("code", code)
                .query().singleRow();
        assertThat(row).containsEntry("custom_alias", false).containsEntry("status", "ACTIVE")
                .containsEntry("target_url", TARGET);
    }

    @Test
    void createsLinkWithAliasAndExpirationInAnyOffset() {
        String alias = "promo-" + shortRandom();
        Instant expiresAt = clock.instant().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        Response response = create(Map.of("url", TARGET, "alias", alias,
                "expiresAt", expiresAt.atOffset(ZoneOffset.ofHours(-3)).toString()));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.text("code")).isEqualTo(alias);
        assertThat(Instant.parse(response.text("expiresAt"))).isEqualTo(expiresAt);
        assertThat(jdbc.sql("select custom_alias from links where code = :code").param("code", alias)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void everyInvalidFieldIsReportedAtOnce() {
        Response response = create(Map.of("url", "javascript:alert(1)", "alias", "Admin",
                "expiresAt", clock.instant().minus(1, ChronoUnit.HOURS).toString()));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.errors("field")).containsExactly("url", "alias", "expiresAt");
        assertThat(response.errors("message")).containsExactly("deve usar http ou https", "é uma palavra reservada",
                "deve estar pelo menos 1 minuto no futuro");
    }

    @Test
    void missingUrlIsAValidationError() {
        Response response = create(Map.of());

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.errors("field")).containsExactly("url");
        assertThat(response.errors("message")).containsExactly("é obrigatória");
    }

    @Test
    void expirationMustFallInsideTheAllowedWindow() {
        Instant now = clock.instant();

        Response tooSoon = create(Map.of("url", TARGET, "expiresAt", now.plusSeconds(30).toString()));
        Response tooLate = create(Map.of("url", TARGET, "expiresAt", now.plus(366, ChronoUnit.DAYS).toString()));

        assertProblem(tooSoon, 400, "VALIDATION_FAILED");
        assertThat(tooSoon.errors("message")).containsExactly("deve estar pelo menos 1 minuto no futuro");
        assertProblem(tooLate, 400, "VALIDATION_FAILED");
        assertThat(tooLate.errors("message")).containsExactly("deve estar no máximo 365 dias no futuro");
    }

    @Test
    void expirationWithoutOffsetIsMalformed() {
        assertProblem(create(Map.of("url", TARGET, "expiresAt", "2030-01-01T00:00:00")), 400, "MALFORMED_REQUEST");
    }

    @Test
    void aliasIsUniqueAcrossUsersButCaseSensitive() {
        String alias = "Oferta-" + shortRandom();
        assertThat(create(Map.of("url", TARGET, "alias", alias)).status()).isEqualTo(201);

        Response sameAlias = api.post(LINKS, Map.of("url", TARGET, "alias", alias), newUserToken());
        Response otherCase = create(Map.of("url", TARGET, "alias", alias.toLowerCase(Locale.ROOT)));

        assertProblem(sameAlias, 409, "ALIAS_TAKEN");
        assertThat(otherCase.status()).isEqualTo(201);
    }

    @Test
    void codeOfADeletedLinkIsNeverReused() {
        String alias = "antigo-" + shortRandom();
        create(Map.of("url", TARGET, "alias", alias));
        assertThat(api.delete(LINKS + "/" + alias, token).status()).isEqualTo(204);

        // Regressão de P11: o código continua reservado, para que um link compartilhado nunca mude de destino.
        assertProblem(create(Map.of("url", "https://outro.com/", "alias", alias)), 409, "ALIAS_TAKEN");
    }

    @Test
    void generatedCodeCollisionIsRetriedWithAnotherCode() {
        String taken = create(Map.of("url", TARGET)).text("code");
        double collisionsBefore = counter("shortener.code.collisions");
        codes.willReturn(taken, 1);

        Response response = create(Map.of("url", TARGET));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.text("code")).isNotEqualTo(taken);
        assertThat(counter("shortener.code.collisions")).isEqualTo(collisionsBefore + 1);
    }

    @Test
    void exhaustingTheCodeAttemptsReturns503() {
        String taken = create(Map.of("url", TARGET)).text("code");
        double collisionsBefore = counter("shortener.code.collisions");
        double exhaustedBefore = counter("shortener.code.exhausted");
        codes.willReturn(taken, 5);

        Response response = create(Map.of("url", TARGET));

        assertProblem(response, 503, "CODE_GENERATION_EXHAUSTED");
        assertThat(counter("shortener.code.collisions")).isEqualTo(collisionsBefore + 5);
        assertThat(counter("shortener.code.exhausted")).isEqualTo(exhaustedBefore + 1);
        assertThat(list(0, 20).body().path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void creatingRequiresBearerToken() {
        Response response = api.post(LINKS, Map.of("url", TARGET), null);

        assertProblem(response, 401, "UNAUTHENTICATED");
    }

    /** O primeiro acesso de um usuário novo do auth-server cria a conta e já pode criar links (ADR-0003, D7). */
    @Test
    void firstAccessOfANewSubjectCreatesTheAccount() {
        String subject = UUID.randomUUID().toString();
        String newcomer = accounts.token(subject);

        Response response = api.post(LINKS, Map.of("url", TARGET), newcomer);

        assertThat(response.status()).isEqualTo(201);
        assertThat(jdbc.sql("select count(*) from accounts where subject = ?").param(subject).query(Long.class)
                .single()).isEqualTo(1);
    }

    /** A conta removida do banco depois de mapeada (o cache ainda a conhece) não ganha links órfãos. */
    @Test
    void tokenOfAnAccountThatNoLongerExistsCannotCreateLinks() {
        TestAccount removed = accounts.create();
        jdbc.sql("delete from accounts where id = ?").param(removed.id()).update();

        Response response = api.post(LINKS, Map.of("url", TARGET), removed.token());

        assertProblem(response, 401, "INVALID_TOKEN");
    }

    // ---- consulta e listagem ----

    @Test
    void ownerSeesTheLinkAndEveryoneElseGets404() {
        String code = create(Map.of("url", TARGET)).text("code");

        Response own = api.get(LINKS + "/" + code, token);
        Response others = api.get(LINKS + "/" + code, newUserToken());

        assertThat(own.status()).isEqualTo(200);
        assertThat(own.text("code")).isEqualTo(code);
        assertThat(own.text("targetUrl")).isEqualTo(TARGET);
        // Mesma resposta de um link inexistente: não revela que o código existe (D9).
        assertProblem(others, 404, "LINK_NOT_FOUND");
        assertProblem(api.get(LINKS + "/naoExiste", token), 404, "LINK_NOT_FOUND");
        assertProblem(api.get(LINKS + "/x", token), 404, "LINK_NOT_FOUND");
    }

    @Test
    void listingIsPaginatedNewestFirstAndSkipsDeletedLinks() {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add(create(Map.of("url", TARGET + i)).text("code"));
            clock.advance(Duration.ofSeconds(1));
        }
        api.delete(LINKS + "/" + created.get(1), token);
        create(Map.of("url", TARGET), newUserToken());

        Response first = list(0, 2);
        Response second = list(1, 2);

        assertThat(first.status()).isEqualTo(200);
        assertThat(codesOf(first)).containsExactly(created.get(3), created.get(2));
        assertThat(codesOf(second)).containsExactly(created.get(0));
        assertThat(first.body().path("page").asInt()).isZero();
        assertThat(first.body().path("size").asInt()).isEqualTo(2);
        assertThat(first.body().path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.body().path("totalPages").asInt()).isEqualTo(2);
        assertThat(codesOf(list(2, 2))).isEmpty();
    }

    @Test
    void expiredLinksStayVisibleWithDerivedStatus() {
        Instant expiresAt = clock.instant().plus(2, ChronoUnit.MINUTES);
        String code = create(Map.of("url", TARGET, "expiresAt", expiresAt.toString())).text("code");

        clock.advance(Duration.ofMinutes(3));

        assertThat(api.get(LINKS + "/" + code, token).text("status")).isEqualTo("EXPIRED");
        assertThat(list(0, 20).body().path("content").path(0).path("status").asString()).isEqualTo("EXPIRED");
        // EXPIRED é derivado: nada foi escrito no banco (regressão de P3).
        assertThat(jdbc.sql("select status from links where code = :code").param("code", code)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void invalidPaginationIsRejected() {
        assertPaginationRejected("size=0", "size");
        assertPaginationRejected("size=101", "size");
        assertPaginationRejected("page=-1", "page");
        assertPaginationRejected("page=10001", "page");
    }

    // ---- remoção ----

    @Test
    void deletionIsSoftAndOnlyTheOwnerCanDelete() {
        String code = create(Map.of("url", TARGET)).text("code");

        assertProblem(api.delete(LINKS + "/" + code, newUserToken()), 404, "LINK_NOT_FOUND");
        assertThat(api.delete(LINKS + "/" + code, token).status()).isEqualTo(204);

        assertProblem(api.get(LINKS + "/" + code, token), 404, "LINK_NOT_FOUND");
        assertProblem(api.delete(LINKS + "/" + code, token), 404, "LINK_NOT_FOUND");
        Map<String, Object> row = jdbc.sql("select status, deleted_at from links where code = :code")
                .param("code", code)
                .query().singleRow();
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("deleted_at")).isNotNull();
    }

    // ---- apoio ----

    private Response create(Map<String, ?> body) {
        return create(body, token);
    }

    private Response create(Map<String, ?> body, String bearerToken) {
        return api.post(LINKS, new HashMap<>(body), bearerToken);
    }

    private Response list(int page, int size) {
        return api.get(LINKS + "?page=" + page + "&size=" + size, token);
    }

    private void assertPaginationRejected(String query, String field) {
        Response response = api.get(LINKS + "?" + query, token);
        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.errors("field")).containsExactly(field);
    }

    private String newUserToken() {
        return accounts.create().token();
    }

    private double counter(String name) {
        return meters.counter(name).count();
    }

    private static List<String> codesOf(Response page) {
        List<String> codes = new ArrayList<>();
        for (JsonNode link : page.body().path("content")) {
            codes.add(link.path("code").asString());
        }
        return codes;
    }

    private static String shortRandom() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo(code);
    }
}
