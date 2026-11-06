package br.com.encurtador.analytics;

import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.TestAccounts;
import br.com.encurtador.support.TestAccounts.TestAccount;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estatísticas de todos os links do usuário (ADR-0004, D7), com cliques gravados direto no banco.
 */
@IntegrationTest
class UserStatsIntegrationTest {

    private static final String PERIOD = "from=2026-10-01&to=2026-10-03&tz=America/Sao_Paulo";

    @LocalServerPort
    int port;

    @Autowired
    LinkService links;

    @Autowired
    TestAccounts accounts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    private ApiClient api;
    private TestAccount owner;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        owner = accounts.create();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void sumsTheActiveAndExpiredLinksOfTheUserOnly() {
        String active = create(owner, null);
        String expiring = create(owner, clock.instant().plus(Duration.ofMinutes(5)));
        String removed = create(owner, null);
        String others = create(accounts.create(), null);
        click(active, "2026-10-01T12:00:00Z", "t.co");
        click(active, "2026-10-01T13:00:00Z", null);
        click(active, "2026-10-03T12:00:00Z", "t.co");
        click(expiring, "2026-10-02T12:00:00Z", "exemplo.com");
        click(active, "2026-09-01T12:00:00Z", "fora.do.periodo");
        click(removed, "2026-10-02T12:00:00Z", "removido.com");
        click(others, "2026-10-02T12:00:00Z", "outro.com");
        links.delete(owner.id(), removed);
        clock.advance(Duration.ofMinutes(10));

        Response response = stats(owner, PERIOD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("totalClicks").asLong()).isEqualTo(5);
        assertThat(response.body().path("periodClicks").asLong()).isEqualTo(4);
        assertThat(response.text("from")).isEqualTo("2026-10-01");
        assertThat(response.text("to")).isEqualTo("2026-10-03");
        assertThat(response.text("timezone")).isEqualTo("America/Sao_Paulo");
        assertThat(daily(response)).containsExactly("2026-10-01=2", "2026-10-02=1", "2026-10-03=1");
        assertThat(topLinks(response)).containsExactly(active + "=3:ACTIVE", expiring + "=1:EXPIRED");
        assertThat(topReferrers(response)).containsExactly("t.co=2", "null=1", "exemplo.com=1");
    }

    @Test
    void userWithoutLinksGetsZerosForEveryDay() {
        Response response = stats(owner, PERIOD);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("totalClicks").asLong()).isZero();
        assertThat(response.body().path("periodClicks").asLong()).isZero();
        assertThat(daily(response)).containsExactly("2026-10-01=0", "2026-10-02=0", "2026-10-03=0");
        assertThat(response.body().path("topLinks").isEmpty()).isTrue();
        assertThat(response.body().path("topReferrers").isEmpty()).isTrue();
    }

    @Test
    void topLinksHasAtMostTenItemsOrderedByClicks() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String code = create(owner, null);
            codes.add(code);
            for (int c = 0; c <= i; c++) {
                click(code, "2026-10-02T12:00:00Z", null);
            }
        }

        List<String> top = topLinks(stats(owner, PERIOD));

        assertThat(top).hasSize(10);
        assertThat(top.getFirst()).isEqualTo(codes.get(11) + "=12:ACTIVE");
        assertThat(top.getLast()).isEqualTo(codes.get(2) + "=3:ACTIVE");
    }

    @Test
    void periodAndZoneFollowTheRulesOfTheLinkStats() {
        assertProblem(stats(owner, "tz=Mars/Olympus"), 400, "VALIDATION_FAILED");
        assertProblem(stats(owner, "from=2026-01-01&to=2026-12-31"), 400, "VALIDATION_FAILED");
        assertThat(stats(owner, "").body().path("daily").size()).isEqualTo(30);
    }

    @Test
    void requiresBearerToken() {
        assertProblem(api.get("/api/v1/stats", null), 401, "UNAUTHENTICATED");
    }

    private Response stats(TestAccount account, String query) {
        return api.get("/api/v1/stats" + (query.isEmpty() ? "" : "?" + query), account.token());
    }

    private String create(TestAccount account, @Nullable Instant expiresAt) {
        return links.create(account.id(), "https://exemplo.com/" + UUID.randomUUID(), null, expiresAt).code();
    }

    private void click(String code, String occurredAt, @Nullable String referrerHost) {
        long linkId = jdbc.sql("select id from links where code = ?").param(code).query(Long.class).single();
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

    private static List<String> daily(Response response) {
        List<String> days = new ArrayList<>();
        for (JsonNode day : response.body().path("daily")) {
            days.add(day.path("date").asString() + "=" + day.path("clicks").asLong());
        }
        return days;
    }

    private static List<String> topLinks(Response response) {
        List<String> top = new ArrayList<>();
        for (JsonNode link : response.body().path("topLinks")) {
            top.add(link.path("code").asString() + "=" + link.path("clicks").asLong() + ":"
                    + link.path("status").asString());
        }
        return top;
    }

    private static List<String> topReferrers(Response response) {
        List<String> top = new ArrayList<>();
        for (JsonNode referrer : response.body().path("topReferrers")) {
            String host = referrer.path("host").isNull() ? "null" : referrer.path("host").asString();
            top.add(host + "=" + referrer.path("clicks").asLong());
        }
        return top;
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValue("application/problem+json");
        assertThat(response.text("code")).isEqualTo(code);
    }
}
