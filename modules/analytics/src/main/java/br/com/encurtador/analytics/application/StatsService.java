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
import java.util.function.Function;
import java.util.stream.Collectors;

/** Estatísticas de um link do usuário e de todos os links dele (RF08; ADR-0001, D8; ADR-0004, D7). */
@Service
public class StatsService {

    public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";

    static final int MAX_DAYS = 90;
    static final int DEFAULT_DAYS = 30;
    static final int TOP_REFERRERS = 10;
    static final int TOP_LINKS = 10;
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
        Period period = period(from, to, timezone);
        LinkSnapshot link = links.findOwned(code, ownerId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                LINK_NOT_FOUND, "Link não encontrado."));
        long[] ids = {link.id()};
        return new LinkStats(link.code(), clicks.countAll(ids), clicks.countBetween(ids, period.from(), period.to()),
                period.start(), period.end(), period.zone(), daily(ids, period), referrers(ids, period));
    }

    /**
     * Visão agregada de todos os links não removidos do usuário, ativos e expirados (ADR-0004, D7), com as mesmas
     * regras de período e fuso das estatísticas de um link. Um usuário sem links recebe zeros, e não 404.
     */
    public UserStats userStats(long ownerId, @Nullable LocalDate from, @Nullable LocalDate to,
                               @Nullable String timezone) {
        Period period = period(from, to, timezone);
        Map<Long, LinkSnapshot> owned = links.ownedLinks(ownerId).stream()
                .collect(Collectors.toMap(LinkSnapshot::id, Function.identity()));
        long[] ids = owned.keySet().stream().mapToLong(Long::longValue).toArray();
        Instant now = clock.instant();
        List<UserStats.TopLink> topLinks = clicks.topLinks(ids, period.from(), period.to(), TOP_LINKS).stream()
                .map(count -> {
                    LinkSnapshot link = owned.get(count.linkId());
                    return new UserStats.TopLink(link.code(), count.clicks(),
                            link.isExpiredAt(now) ? UserStats.LinkState.EXPIRED : UserStats.LinkState.ACTIVE);
                })
                .toList();
        return new UserStats(clicks.countAll(ids), clicks.countBetween(ids, period.from(), period.to()),
                period.start(), period.end(), period.zone(), daily(ids, period), topLinks, referrers(ids, period));
    }

    private Period period(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String timezone) {
        List<FieldViolation> violations = new ArrayList<>();
        ZoneId zone = resolveZone(timezone, violations);
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        checkPeriod(start, end, violations);
        if (!violations.isEmpty()) {
            throw new InvalidFieldsException(violations);
        }
        return new Period(start, end, zone);
    }

    /** Um item por dia do período, com zero nos dias sem clique. */
    private List<LinkStats.Day> daily(long[] ids, Period period) {
        Map<LocalDate, Long> perDay = clicks.countByDay(ids, period.from(), period.to(), period.zone()).stream()
                .collect(Collectors.toMap(DailyCount::day, DailyCount::clicks));
        return period.start().datesUntil(period.end().plusDays(1))
                .map(day -> new LinkStats.Day(day, perDay.getOrDefault(day, 0L)))
                .toList();
    }

    private List<LinkStats.Referrer> referrers(long[] ids, Period period) {
        return clicks.topReferrers(ids, period.from(), period.to(), TOP_REFERRERS).stream()
                .map(referrer -> new LinkStats.Referrer(referrer.host(), referrer.clicks()))
                .toList();
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

    /**
     * Dias locais de {@code start} a {@code end}, inclusive, no fuso {@code zone}; {@link #from()} e {@link #to()} são
     * os instantes que os delimitam, com o fim aberto.
     */
    private record Period(LocalDate start, LocalDate end, ZoneId zone) {

        Instant from() {
            return start.atStartOfDay(zone).toInstant();
        }

        Instant to() {
            return end.plusDays(1).atStartOfDay(zone).toInstant();
        }
    }
}
