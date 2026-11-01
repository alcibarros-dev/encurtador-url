package br.com.encurtador.analytics;

import br.com.encurtador.identity.application.RegistrationService;
import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.support.ApiClient;
import br.com.encurtador.support.ApiClient.Response;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Do redirecionamento às estatísticas, com o Kafka de verdade (ADR-0001, D8; cenários 1, 7, 10 e 14 do D14).
 */
@IntegrationTest
class ClickPipelineIntegrationTest {

    private static final String TOPIC = "link-clicks";
    private static final String DEAD_LETTER_TOPIC = "link-clicks-dlt";
    private static final Duration PIPELINE = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    @Autowired
    LinkService links;

    @Autowired
    RegistrationService registration;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ConsumerFactory<String, String> consumers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    MutableClock clock;

    private ApiClient api;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    /** Cenário 1. */
    @Test
    void clickOnARedirectShowsUpInTheStats() throws Exception {
        String email = "pipeline-" + UUID.randomUUID() + "@exemplo.com";
        assertThat(api.post("/api/v1/auth/register", Map.of("email", email, "password", "senha-segura-1")).status())
                .isEqualTo(201);
        String token = api.post("/api/v1/auth/login", Map.of("email", email, "password", "senha-segura-1"))
                .text("accessToken");
        String code = api.post("/api/v1/links", Map.of("url", "https://exemplo.com/artigo"), token).text("code");

        HttpResponse<Void> redirect = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + code))
                .header("Referer", "https://t.co/abc123?utm=segredo").GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(redirect.statusCode()).isEqualTo(302);

        String statsPath = "/api/v1/links/" + code + "/stats";
        await().atMost(PIPELINE).until(() -> api.get(statsPath, token).body().path("totalClicks").asLong() == 1);
        Response stats = api.get(statsPath, token);
        assertThat(stats.body().path("periodClicks").asLong()).isEqualTo(1);
        assertThat(stats.body().path("topReferrers").path(0).path("host").asString()).isEqualTo("t.co");
        assertThat(stats.body().path("topReferrers").path(0).path("clicks").asLong()).isEqualTo(1);
        // O último dia da série padrão é hoje.
        JsonNode daily = stats.body().path("daily");
        assertThat(daily.path(daily.size() - 1).path("clicks").asLong()).isEqualTo(1);
    }

    /** Cenário 10: o instante gravado é o do redirecionamento, mesmo que o consumo aconteça muito depois. */
    @Test
    void occurredAtIsTheRedirectInstantNotTheConsumptionInstant() throws Exception {
        long linkId = newLinkId();
        Instant before = clock.instant().truncatedTo(ChronoUnit.MICROS);
        assertThat(api.get("/" + codeOf(linkId), null).status()).isEqualTo(302);
        Instant after = clock.instant();
        await().atMost(PIPELINE).until(() -> clicksOf(linkId) == 1);
        assertThat(occurredAtOf(linkId)).isBetween(before, after);

        // Evento atrasado três dias (ex.: ficou parado no Kafka): o consumidor não troca o instante pelo dele.
        Instant threeDaysAgo = Instant.parse("2026-10-29T08:15:30.123456Z");
        LinkClicked late = new LinkClicked(1, UUID.randomUUID(), linkId, threeDaysAgo, null);
        send(linkId, json.writeValueAsString(late));
        await().atMost(PIPELINE).until(() -> clicksOf(linkId) == 2);
        assertThat(jdbc.sql("select occurred_at from click_events where event_id = :id").param("id", late.eventId())
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(threeDaysAgo);
    }

    /** Cenário 14 (parte do clique): HEAD redireciona, mas não conta. */
    @Test
    void headIsNotAClick() {
        long linkId = newLinkId();
        String code = codeOf(linkId);

        assertThat(api.head("/" + code).status()).isEqualTo(302);
        assertThat(api.head("/" + code).status()).isEqualTo(302);
        assertThat(api.get("/" + code, null).status()).isEqualTo(302);

        // Mesma chave, mesma partição e fila FIFO: se um HEAD tivesse gerado clique, ele chegaria antes do GET.
        await().atMost(PIPELINE).until(() -> clicksOf(linkId) >= 1);
        assertThat(clicksOf(linkId)).isEqualTo(1);
    }

    /** Cenário 7: entrega repetida gera um único registro. */
    @Test
    void duplicateDeliveryIsStoredOnce() throws Exception {
        long linkId = newLinkId();
        String duplicate = json.writeValueAsString(new LinkClicked(1, UUID.randomUUID(), linkId, clock.instant(), null));
        LinkClicked marker = new LinkClicked(1, UUID.randomUUID(), linkId, clock.instant(), "marcador.com");

        send(linkId, duplicate);
        send(linkId, duplicate);
        send(linkId, json.writeValueAsString(marker));

        // O marcador vem depois das duas cópias na mesma partição: quando ele está gravado, as cópias já passaram.
        await().atMost(PIPELINE).until(() -> jdbc.sql("select count(*) from click_events where event_id = :id")
                .param("id", marker.eventId()).query(Long.class).single() == 1);
        assertThat(clicksOf(linkId)).isEqualTo(2);
    }

    /** Cenário 7: mensagem que nunca vai ser gravada vai para a DLT, e o consumidor segue em frente. */
    @Test
    void poisonMessagesGoToTheDeadLetterTopic() throws Exception {
        String unreadable = "não é json " + UUID.randomUUID();
        String unknownLink = json.writeValueAsString(
                new LinkClicked(1, UUID.randomUUID(), Long.MAX_VALUE, clock.instant(), null));
        String unknownVersion = json.writeValueAsString(
                new LinkClicked(2, UUID.randomUUID(), newLinkId(), clock.instant(), null));
        String incomplete = "{\"v\":1,\"linkId\":" + newLinkId() + ",\"marca\":\"" + UUID.randomUUID() + "\"}";

        kafka.send(TOPIC, "veneno", unreadable).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "veneno", unknownLink).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "veneno", unknownVersion).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, "veneno", incomplete).get(10, TimeUnit.SECONDS);

        List<ConsumerRecord<String, String>> dead = readDeadLetters(
                List.of(unreadable, unknownLink, unknownVersion, incomplete));
        assertThat(exceptionOf(dead, unreadable)).contains("InvalidClickEventException");
        assertThat(exceptionOf(dead, unknownLink)).contains("DataIntegrityViolationException");
        assertThat(exceptionOf(dead, unknownVersion)).contains("InvalidClickEventException");
        assertThat(exceptionOf(dead, incomplete)).contains("InvalidClickEventException");

        // O consumidor não travou na mensagem envenenada.
        long linkId = newLinkId();
        api.get("/" + codeOf(linkId), null);
        await().atMost(PIPELINE).until(() -> clicksOf(linkId) == 1);
    }

    private List<ConsumerRecord<String, String>> readDeadLetters(List<String> expected) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (Consumer<String, String> consumer = consumers.createConsumer("teste-dlt-" + UUID.randomUUID(), null,
                null, overrides)) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            await().atMost(PIPELINE).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(received::add);
                return received.stream().map(ConsumerRecord::value).toList().containsAll(expected);
            });
        }
        return received;
    }

    /** Classe da exceção registrada pelo recoverer, incluindo a causa. */
    private static String exceptionOf(List<ConsumerRecord<String, String>> records, String value) {
        ConsumerRecord<String, String> record = records.stream().filter(r -> value.equals(r.value())).findFirst()
                .orElseThrow();
        StringBuilder classes = new StringBuilder();
        for (Header header : record.headers()) {
            if (header.key().startsWith("kafka_dlt-exception") && header.key().endsWith("fqcn")) {
                classes.append(new String(header.value(), StandardCharsets.UTF_8)).append(' ');
            }
        }
        return classes.toString();
    }

    private void send(long linkId, String value) throws Exception {
        kafka.send(TOPIC, Long.toString(linkId), value).get(10, TimeUnit.SECONDS);
    }

    private long newLinkId() {
        long owner = registration.register("clique-" + UUID.randomUUID() + "@exemplo.com", "senha-segura-1").id();
        String code = links.create(owner, "https://exemplo.com/", null, null).code();
        return jdbc.sql("select id from links where code = :code").param("code", code).query(Long.class).single();
    }

    private String codeOf(long linkId) {
        return jdbc.sql("select code from links where id = :id").param("id", linkId).query(String.class).single();
    }

    private long clicksOf(long linkId) {
        return jdbc.sql("select count(*) from click_events where link_id = :id").param("id", linkId)
                .query(Long.class).single();
    }

    private Instant occurredAtOf(long linkId) {
        return jdbc.sql("select occurred_at from click_events where link_id = :id").param("id", linkId)
                .query(OffsetDateTime.class).single().toInstant();
    }
}
