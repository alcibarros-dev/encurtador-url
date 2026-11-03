package br.com.encurtador.analytics.infra;

import br.com.encurtador.analytics.LinkClicked;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Grava os cliques, um registro por vez (ADR-0001, D8). Na carga alvo (~4 eventos/s), consumir em lote não traz
 * ganho e complicaria mandar só a mensagem ruim para a DLT.
 * <p>
 * Idempotente diante do at-least-once do Kafka: o mesmo {@code eventId} entregue duas vezes gera um único registro.
 */
@Component
class ClickConsumer {

    private final ClickEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    ClickConsumer(ClickEventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @KafkaListener(id = "click-consumer", topics = "${app.analytics.topic}")
    void onClick(ConsumerRecord<String, String> record) {
        events.insertIfAbsent(parse(record.value()), clock.instant());
    }

    /**
     * O valor chega como texto e é lido aqui, com tipo fixo e sem cabeçalho de tipo: qualquer falha vira
     * {@link InvalidClickEventException}, que o error handler manda para a DLT sem retry.
     */
    private LinkClicked parse(String value) {
        if (value == null) {
            throw new InvalidClickEventException("Mensagem sem valor");
        }
        LinkClicked click;
        try {
            click = json.readValue(value, LinkClicked.class);
        } catch (JacksonException ex) {
            throw new InvalidClickEventException("JSON inválido", ex);
        }
        if (click.v() != LinkClicked.VERSION) {
            throw new InvalidClickEventException("Versão de evento desconhecida: " + click.v());
        }
        if (click.eventId() == null || click.occurredAt() == null || click.linkId() <= 0) {
            throw new InvalidClickEventException("Evento sem eventId, occurredAt ou linkId");
        }
        if (click.referrerHost() != null && click.referrerHost().length() > LinkClicked.MAX_REFERRER_HOST_LENGTH) {
            throw new InvalidClickEventException("referrerHost acima de " + LinkClicked.MAX_REFERRER_HOST_LENGTH);
        }
        return click;
    }
}
