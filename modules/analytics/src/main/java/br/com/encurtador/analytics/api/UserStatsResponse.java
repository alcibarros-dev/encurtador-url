package br.com.encurtador.analytics.api;

import br.com.encurtador.analytics.application.UserStats;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** Formato do ADR-0004, D7: o de um link, sem {@code code} e com {@code topLinks}. */
record UserStatsResponse(long totalClicks, long periodClicks, LocalDate from, LocalDate to, String timezone,
                         List<LinkStatsResponse.Day> daily, List<TopLink> topLinks,
                         List<LinkStatsResponse.Referrer> topReferrers) {

    @Schema(name = "LinkClicks")
    record TopLink(String code, long clicks,
                   @Schema(description = "Removidos não entram", allowableValues = {"ACTIVE", "EXPIRED"})
                   String status) {
    }

    static UserStatsResponse from(UserStats stats) {
        return new UserStatsResponse(stats.totalClicks(), stats.periodClicks(), stats.from(), stats.to(),
                stats.zone().getId(),
                stats.daily().stream().map(day -> new LinkStatsResponse.Day(day.date(), day.clicks())).toList(),
                stats.topLinks().stream().map(link -> new TopLink(link.code(), link.clicks(), link.status().name()))
                        .toList(),
                stats.topReferrers().stream().map(ref -> new LinkStatsResponse.Referrer(ref.host(), ref.clicks()))
                        .toList());
    }
}
