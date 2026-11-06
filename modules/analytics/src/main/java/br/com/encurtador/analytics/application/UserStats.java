package br.com.encurtador.analytics.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Estatísticas de todos os links não removidos de um usuário (ADR-0004, D7).
 *
 * @param totalClicks  todos os cliques já gravados dos links
 * @param periodClicks cliques de {@code from} a {@code to}, inclusive, no fuso {@code zone}
 * @param daily        um item por dia do período, com zero nos dias sem clique
 * @param topLinks     até 10 links com mais cliques no período
 * @param topReferrers até 10 hosts no período, do mais frequente ao menos; {@code host} nulo é acesso direto
 */
public record UserStats(long totalClicks, long periodClicks, LocalDate from, LocalDate to, ZoneId zone,
                        List<LinkStats.Day> daily, List<TopLink> topLinks, List<LinkStats.Referrer> topReferrers) {

    /** Removidos não entram: só sobram ativos e expirados. */
    public enum LinkState {
        ACTIVE, EXPIRED
    }

    public record TopLink(String code, long clicks, LinkState status) {
    }
}
