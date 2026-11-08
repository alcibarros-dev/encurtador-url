package br.com.encurtador.clicks;

import br.com.encurtador.support.ClickTopic;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.RedirectClient;
import br.com.encurtador.support.RedirectTest;
import br.com.encurtador.support.TestLinks;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.ConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O clique sai do redirect para o tópico que o analytics da API consome (ADR-0001, D8; cenários 1, 10 e 14 do D14;
 * ADR-0006, D5). Daqui em diante, o caminho é testado na API.
 */
@RedirectTest
class ClickPublishingIntegrationTest {

    private static final Duration PIPELINE = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    @Autowired
    TestLinks links;

    @Autowired
    MutableClock clock;

    @Autowired
    JsonMapper json;

    @Autowired
    ConsumerFactory<String, String> consumers;

    private RedirectClient client;
    private ClickTopic topic;

    @BeforeEach
    void setUp() {
        client = new RedirectClient(port);
        topic = new ClickTopic(consumers);
    }

    /** Cenários 1 e 10: o evento leva o instante do redirecionamento e, do Referer, só o host (RNF08). */
    @Test
    void redirectPublishesTheClickKeyedByLinkId() {
        TestLinks.Link link = links.active("https://exemplo.com/artigo");
        Instant before = clock.instant();

        assertThat(client.get("/" + link.code(), "Referer", "https://t.co/abc123?utm=segredo").status())
                .isEqualTo(302);
        Instant after = clock.instant();

        List<ConsumerRecord<String, String>> records = topic.recordsOf(link.id(), PIPELINE, r -> !r.isEmpty());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().value()).doesNotContain("segredo", "abc123");
        LinkClicked click = json.readValue(records.getFirst().value(), LinkClicked.class);
        assertThat(click.v()).isEqualTo(LinkClicked.VERSION);
        assertThat(click.linkId()).isEqualTo(link.id());
        assertThat(click.referrerHost()).isEqualTo("t.co");
        assertThat(click.eventId()).isNotNull();
        assertThat(click.occurredAt()).isBetween(before, after);
    }

    /** Cenário 14 (parte do clique): HEAD redireciona, mas não conta. */
    @Test
    void headIsNotAClick() {
        TestLinks.Link link = links.active("https://exemplo.com/");

        assertThat(client.head("/" + link.code()).status()).isEqualTo(302);
        assertThat(client.head("/" + link.code()).status()).isEqualTo(302);
        assertThat(client.get("/" + link.code()).status()).isEqualTo(302);

        // Mesma chave, mesma partição e fila FIFO: se um HEAD tivesse gerado clique, ele chegaria antes do GET.
        List<ConsumerRecord<String, String>> records = topic.recordsOf(link.id(), PIPELINE, r -> !r.isEmpty());
        assertThat(records).hasSize(1);
    }
}
