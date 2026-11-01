package br.com.encurtador.analytics.application;

import br.com.encurtador.analytics.infra.ClickEventRepository;
import br.com.encurtador.analytics.infra.ClickEventRepository.DailyCount;
import br.com.encurtador.link.LinkLookup;
import br.com.encurtador.link.LinkSnapshot;
import br.com.encurtador.shared.error.ApiException;
import br.com.encurtador.shared.error.FieldViolation;
import br.com.encurtador.shared.error.InvalidFieldsException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Estatísticas de um link do usuário (RF08; ADR-0001, D8). */
@Service
public class StatsService {

    public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";

    static final int MAX_DAYS = 90;
    static final int DEFAULT_DAYS = 30;
    static final int TOP_REFERRERS = 10;
    /** O fim do período vira o início do dia seguinte: o limite superior deixa folga para isso. */
    static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 30);
    /**
     * Fusos que o Java e o PostgreSQL conhecem. O Java ainda aceita a família legada {@code SystemV/*}, que saiu da
     * base IANA e que o PostgreSQL recusaria com erro. Cópia única: {@code getAvailableZoneIds()} monta um conjunto
     * novo a cada chamada.
     */
    static final Set<String> IANA_ZONES = ZoneId.getAvailableZoneIds().stream()
            .filter(id -> !id.startsWith("SystemV/"))
            .collect(Collectors.toUnmodifiableSet());

    private final LinkLookup links;
    private final ClickEventRepository clicks;
    private final AnalyticsProperties properties;
    private final Clock clock;

    public StatsService(LinkLookup links, ClickEventRepository clicks, AnalyticsProperties properties, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sem {@code to}, o período termina hoje no fuso pedido; sem {@code from}, cobre 30 dias até {@code to}. Links
     * removidos respondem 404, e os cliques que chegarem depois da remoção não aparecem.
     */
    public LinkStats stats(long ownerId, String code, @Nullable LocalDate from, @Nullable LocalDate to,
                           @Nullable String timezone) {
        List<FieldViolation> violations = new ArrayList<>();
        ZoneId zone = resolveZone(timezone, violations);
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        checkPeriod(start, end, violations);
        if (!violations.isEmpty()) {
            throw new InvalidFieldsException(violations);
        }
        LinkSnapshot link = links.findOwned(code, ownerId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                LINK_NOT_FOUND, "Link não encontrado."));

        Instant fromInstant = start.atStartOfDay(zone).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(zone).toInstant();
        Map<LocalDate, Long> perDay = clicks.countByDay(link.id(), fromInstant, toInstant, zone).stream()
                .collect(Collectors.toMap(DailyCount::day, DailyCount::clicks));
        List<LinkStats.Day> daily = start.datesUntil(end.plusDays(1))
                .map(day -> new LinkStats.Day(day, perDay.getOrDefault(day, 0L)))
                .toList();
        List<LinkStats.Referrer> referrers = clicks.topReferrers(link.id(), fromInstant, toInstant, TOP_REFERRERS)
                .stream()
                .map(referrer -> new LinkStats.Referrer(referrer.host(), referrer.clicks()))
                .toList();
        return new LinkStats(link.code(), clicks.countAll(link.id()),
                clicks.countBetween(link.id(), fromInstant, toInstant), start, end, zone, daily, referrers);
    }

    /**
     * Só nomes IANA ({@code America/Sao_Paulo}, {@code UTC}): o agrupamento por dia roda no PostgreSQL, que lê
     * offsets como {@code +03:00} no sentido POSIX, com o sinal invertido.
     */
    private ZoneId resolveZone(@Nullable String timezone, List<FieldViolation> violations) {
        if (timezone == null) {
            return properties.defaultZone();
        }
        if (IANA_ZONES.contains(timezone)) {
            return ZoneId.of(timezone);
        }
        violations.add(new FieldViolation("tz", "deve ser um fuso horário IANA, como America/Sao_Paulo ou UTC"));
        return properties.defaultZone();
    }

    private static void checkPeriod(LocalDate start, LocalDate end, List<FieldViolation> violations) {
        boolean startInRange = isInRange(start);
        boolean endInRange = isInRange(end);
        if (!startInRange) {
            violations.add(new FieldViolation("from", "deve estar entre " + MIN_DATE + " e " + MAX_DATE));
        }
        if (!endInRange) {
            violations.add(new FieldViolation("to", "deve estar entre " + MIN_DATE + " e " + MAX_DATE));
        }
        if (!startInRange || !endInRange) {
            return;
        }
        if (start.isAfter(end)) {
            violations.add(new FieldViolation("from", "deve ser anterior ou igual a to"));
        } else if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            violations.add(new FieldViolation("from", "o período pode ter no máximo " + MAX_DAYS + " dias"));
        }
    }

    private static boolean isInRange(LocalDate date) {
        return !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE);
    }
}
