package br.com.encurtador.analytics.api;

import br.com.encurtador.analytics.application.LinkStats;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/** Formato do D12. Em {@code topReferrers}, {@code host} nulo é acesso direto (sem Referer). */
record LinkStatsResponse(String code, long totalClicks, long periodClicks, LocalDate from, LocalDate to,
                         String timezone, List<Day> daily, List<Referrer> topReferrers) {

    @Schema(name = "DailyClicks")
    record Day(LocalDate date, long clicks) {
    }

    @Schema(name = "ReferrerClicks")
    record Referrer(
            @Schema(description = "Nulo para acesso direto, sem Referer", nullable = true) @Nullable String host,
            long clicks) {
    }

    static LinkStatsResponse from(LinkStats stats) {
        return new LinkStatsResponse(stats.code(), stats.totalClicks(), stats.periodClicks(), stats.from(),
                stats.to(), stats.zone().getId(),
                stats.daily().stream().map(day -> new Day(day.date(), day.clicks())).toList(),
                stats.topReferrers().stream().map(ref -> new Referrer(ref.host(), ref.clicks())).toList());
    }
}
