package br.com.encurtador.analytics.infra;

import br.com.encurtador.analytics.LinkClicked;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static br.com.encurtador.shared.time.SqlTimestamps.toSql;

/**
 * Cliques em SQL explícito: inserção idempotente e agregações (ADR-0001, D6 e D8). As agregações recebem os ids dos
 * links, um só para as estatísticas de um link e todos os do usuário para as dele (ADR-0004, D7). Os filtros usam
 * {@code link_id = any(:linkIds) and occurred_at >= :from and occurred_at < :to}, que aproveita o índice
 * {@code (link_id, occurred_at)}. Os ids vão como {@code long[]}: um só parâmetro {@code bigint[]}, que o
 * {@code JdbcClient} não expande em uma lista de parâmetros.
 */
@Repository
public class ClickEventRepository {

    private final JdbcClient jdbc;

    ClickEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code false} se o evento já tinha sido gravado (entrega repetida do Kafka) */
    boolean insertIfAbsent(LinkClicked click, Instant receivedAt) {
        return jdbc.sql("""
                        insert into click_events (event_id, link_id, occurred_at, referrer_host, received_at)
                        values (:eventId, :linkId, :occurredAt, :referrerHost, :receivedAt)
                        on conflict (event_id) do nothing
                        """)
                .param("eventId", click.eventId())
                .param("linkId", click.linkId())
                .param("occurredAt", toSql(click.occurredAt()))
                .param("referrerHost", click.referrerHost())
                .param("receivedAt", toSql(receivedAt))
                .update() == 1;
    }

    /** Todos os cliques já gravados dos links. */
    public long countAll(long[] linkIds) {
        return jdbc.sql("select count(*) from click_events where link_id = any(:linkIds)")
                .param("linkIds", linkIds)
                .query(Long.class)
                .single();
    }

    public long countBetween(long[] linkIds, Instant from, Instant to) {
        return jdbc.sql("""
                        select count(*) from click_events
                         where link_id = any(:linkIds) and occurred_at >= :from and occurred_at < :to
                        """)
                .param("linkIds", linkIds)
                .param("from", toSql(from))
                .param("to", toSql(to))
                .query(Long.class)
                .single();
    }

    /**
     * Agrupa pelo dia local no fuso pedido. O fuso precisa ser um nome IANA: o PostgreSQL lê offsets como
     * {@code +03:00} no sentido POSIX, com o sinal invertido.
     */
    public List<DailyCount> countByDay(long[] linkIds, Instant from, Instant to, ZoneId zone) {
        return jdbc.sql("""
                        select (occurred_at at time zone :zone)::date as day, count(*) as clicks
                          from click_events
                         where link_id = any(:linkIds) and occurred_at >= :from and occurred_at < :to
                         group by day
                         order by day
                        """)
                .param("zone", zone.getId())
                .param("linkIds", linkIds)
                .param("from", toSql(from))
                .param("to", toSql(to))
                .query((rs, row) -> new DailyCount(rs.getObject("day", LocalDate.class), rs.getLong("clicks")))
                .list();
    }

    /** Cliques sem Referer agregados com host {@code null} ("direto"). Empates saem em ordem alfabética. */
    public List<ReferrerCount> topReferrers(long[] linkIds, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        select referrer_host, count(*) as clicks
                          from click_events
                         where link_id = any(:linkIds) and occurred_at >= :from and occurred_at < :to
                         group by referrer_host
                         order by clicks desc, referrer_host nulls first
                         limit :limit
                        """)
                .param("linkIds", linkIds)
                .param("from", toSql(from))
                .param("to", toSql(to))
                .param("limit", limit)
                .query((rs, row) -> new ReferrerCount(rs.getString("referrer_host"), rs.getLong("clicks")))
                .list();
    }

    /** Links com mais cliques no período; empates saem pelo id, o mais antigo primeiro. */
    public List<LinkCount> topLinks(long[] linkIds, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        select link_id, count(*) as clicks
                          from click_events
                         where link_id = any(:linkIds) and occurred_at >= :from and occurred_at < :to
                         group by link_id
                         order by clicks desc, link_id
                         limit :limit
                        """)
                .param("linkIds", linkIds)
                .param("from", toSql(from))
                .param("to", toSql(to))
                .param("limit", limit)
                .query((rs, row) -> new LinkCount(rs.getLong("link_id"), rs.getLong("clicks")))
                .list();
    }

    public record DailyCount(LocalDate day, long clicks) {
    }

    public record ReferrerCount(@Nullable String host, long clicks) {
    }

    public record LinkCount(long linkId, long clicks) {
    }
}
