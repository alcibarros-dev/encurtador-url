package br.com.encurtador.analytics.application;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Estatísticas de um link (RF08; ADR-0001, D8).
 *
 * @param totalClicks  todos os cliques já gravados
 * @param periodClicks cliques de {@code from} a {@code to}, inclusive, no fuso {@code zone}
 * @param daily        um item por dia do período, com zero nos dias sem clique
 * @param topReferrers até 10 hosts, do mais frequente ao menos; {@code host} nulo é acesso direto
 */
public record LinkStats(String code, long totalClicks, long periodClicks, LocalDate from, LocalDate to, ZoneId zone,
                        List<Day> daily, List<Referrer> topReferrers) {

    public record Day(LocalDate date, long clicks) {
    }

    public record Referrer(@Nullable String host, long clicks) {
    }
}
