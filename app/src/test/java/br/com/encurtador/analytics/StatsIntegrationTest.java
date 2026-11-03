package br.com.encurtador.analytics;

import br.com.encurtador.identity.application.AccessTokenIssuer;
import br.com.encurtador.identity.application.RegistrationService;
import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estatísticas com cliques gravados direto no banco, sem passar pelo Kafka (ADR-0001, D8 e D12).
 */
@IntegrationTest
class StatsIntegrationTest {

    private static final String TARGET = "https://exemplo.com/";

    @LocalServerPort
    int port;

    @Autowired
    LinkService links;

    @Autowired
    RegistrationService registration;

    @Autowired
    AccessTokenIssuer tokenIssuer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    private ApiClient api;
    private long owner;
    private String token;
    private String code;
    private long linkId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        owner = newUser();
        token = tokenIssuer.issue(owner).value();
        code = links.create(owner, TARGET, null, null).code();
        linkId = jdbc.sql("select id from links where code = :code").param("code", code).query(Long.class).single();
    }

    @Test
    void countsPerLocalDayInTheRequestedZoneAndFillsEmptyDays() {
        click("2026-10-10T02:30:00Z", "t.co");   // ainda é dia 9 em São Paulo (UTC-3)
        click("2026-10-10T12:00:00Z", null);
        click("2026-10-12T15:00:00Z", "t.co");
        click("2026-09-01T12:00:00Z", "antigo.com.br");

        Response saoPaulo = stats("from=2026-10-09&to=2026-10-12&tz=America/Sao_Paulo");
        Response utc = stats("from=2026-10-09&to=2026-10-12&tz=UTC");

        assertThat(saoPaulo.status()).isEqualTo(200);
        assertThat(saoPaulo.text("code")).isEqualTo(code);
        assertThat(saoPaulo.text("from")).isEqualTo("2026-10-09");
        assertThat(saoPaulo.text("to")).isEqualTo("2026-10-12");
        assertThat(saoPaulo.text("timezone")).isEqualTo("America/Sao_Paulo");
        assertThat(saoPaulo.body().path("totalClicks").asLong()).isEqualTo(4);
        assertThat(saoPaulo.body().path("periodClicks").asLong()).isEqualTo(3);
        assertThat(daily(saoPaulo)).containsExactly("2026-10-09=1", "2026-10-10=1", "2026-10-11=0", "2026-10-12=1");
        assertThat(daily(utc)).containsExactly("2026-10-09=0", "2026-10-10=2", "2026-10-11=0", "2026-10-12=1");
    }

    @Test
    void topReferrersAreOrderedByClicksWithDirectAccessAsNull() {
        String day = "2026-10-15T12:00:00Z";
        for (int i = 0; i < 3; i++) {
            click(day, "t.co");
        }
        click(day, null);
        click(day, null);
        click(day, "a.com.br");
        click(day, "a.com.br");
        for (int i = 0; i < 9; i++) {
            click(day, "site" + i + ".com");
        }

        Response response = stats("from=2026-10-15&to=2026-10-15&tz=UTC");

        List<String> referrers = new ArrayList<>();
        for (JsonNode referrer : response.body().path("topReferrers")) {
            String host = referrer.path("host").isNull() ? "null" : referrer.path("host").asString();
            referrers.add(host + "=" + referrer.path("clicks").asLong());
        }
        // Empate em 2 cliques: acesso direto primeiro, depois ordem alfabética. Só os 10 primeiros.
        assertThat(referrers).hasSize(10).startsWith("t.co=3", "null=2", "a.com.br=2", "site0.com=1");
        assertThat(response.body().path("topReferrers").path(1).path("host").isNull()).isTrue();
    }

    @Test
    void withoutParametersCoversTheLast30DaysInTheDefaultZone() {
        Response response = stats("");

        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text("timezone")).isEqualTo("America/Sao_Paulo");
        assertThat(response.text("to")).isEqualTo(today.toString());
        assertThat(response.text("from")).isEqualTo(today.minusDays(29).toString());
        assertThat(response.body().path("daily").size()).isEqualTo(30);
    }

    @Test
    void invalidParametersAreReportedTogether() {
        Response response = stats("from=2026-10-20&to=2026-10-10&tz=Marte/Olimpo");

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.errors("field")).containsExactly("tz", "from");
        assertThat(response.errors("message")).containsExactly(
                "deve ser um fuso horário IANA, como America/Sao_Paulo ou UTC", "deve ser anterior ou igual a to");
    }

    @Test
    void periodIsLimitedTo90Days() {
        assertThat(stats("from=2026-01-01&to=2026-03-31&tz=UTC").status()).isEqualTo(200);

        Response tooLong = stats("from=2026-01-01&to=2026-04-01&tz=UTC");
        assertProblem(tooLong, 400, "VALIDATION_FAILED");
        assertThat(tooLong.errors("message")).containsExactly("o período pode ter no máximo 90 dias");
    }

    @Test
    void offsetsAndMalformedDatesAreRejected() {
        // O PostgreSQL leria "+03:00" como POSIX, com o sinal invertido.
        assertThat(stats("tz=%2B03:00").errors("field")).containsExactly("tz");
        assertThat(stats("from=ontem").errors("field")).containsExactly("from");
        assertThat(stats("from=2026-01-01&to=%2B999999999-12-31").errors("field")).containsExactly("to");
    }

    @Test
    void linkOfAnotherUserOrDeletedLinkIs404() {
        String others = tokenIssuer.issue(newUser()).value();

        assertProblem(api.get("/api/v1/links/" + code + "/stats", others), 404, "LINK_NOT_FOUND");
        links.delete(owner, code);
        assertProblem(stats(""), 404, "LINK_NOT_FOUND");
    }

    @Test
    void statsRequireBearerToken() {
        assertProblem(api.get("/api/v1/links/" + code + "/stats", null), 401, "UNAUTHENTICATED");
    }

    /**
     * O agrupamento por dia roda no PostgreSQL, que não conhece todos os fusos do Java: nenhum deles pode virar 500.
     * Os desconhecidos do banco (família legada {@code SystemV/*}) são recusados com 400.
     */
    @Test
    void noZoneKnownToJavaCausesAServerError() {
        Set<String> postgres = new HashSet<>(jdbc.sql("select name from pg_timezone_names").query(String.class).list());
        List<String> failures = new ArrayList<>();
        for (String zone : ZoneId.getAvailableZoneIds()) {
            int status = stats("from=2026-10-01&to=2026-10-02&tz=" + zone.replace("+", "%2B")).status();
            int expected = postgres.contains(zone) ? 200 : 400;
            if (status != expected) {
                failures.add(zone + " -> " + status);
            }
        }

        assertThat(failures).isEmpty();
    }

    private Response stats(String query) {
        return api.get("/api/v1/links/" + code + "/stats" + (query.isEmpty() ? "" : "?" + query), token);
    }

    private void click(String occurredAt, String referrerHost) {
        jdbc.sql("""
                        insert into click_events (event_id, link_id, occurred_at, referrer_host, received_at)
                        values (:eventId, :linkId, :occurredAt, :referrerHost, :occurredAt)
                        """)
                .param("eventId", UUID.randomUUID())
                .param("linkId", linkId)
                .param("occurredAt", OffsetDateTime.ofInstant(Instant.parse(occurredAt), ZoneOffset.UTC))
                .param("referrerHost", referrerHost)
                .update();
    }

    private long newUser() {
        return registration.register("stats-" + UUID.randomUUID() + "@exemplo.com", "senha-segura-1").id();
    }

    private static List<String> daily(Response response) {
        List<String> days = new ArrayList<>();
        for (JsonNode day : response.body().path("daily")) {
            days.add(day.path("date").asString() + "=" + day.path("clicks").asLong());
        }
        return days;
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo(code);
    }
}
