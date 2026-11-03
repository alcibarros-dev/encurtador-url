package br.com.encurtador.link.application;

import br.com.encurtador.link.LinkDeleted;
import br.com.encurtador.link.LinkStatus;
import br.com.encurtador.link.domain.Alias;
import br.com.encurtador.link.domain.HostClassifier;
import br.com.encurtador.link.domain.InvalidValueException;
import br.com.encurtador.link.domain.ShortCode;
import br.com.encurtador.link.domain.TargetUrl;
import br.com.encurtador.link.infra.LinkRepository;
import br.com.encurtador.shared.config.AppProperties;
import br.com.encurtador.shared.error.ApiException;
import br.com.encurtador.shared.error.FieldViolation;
import br.com.encurtador.shared.error.InvalidFieldsException;
import br.com.encurtador.shared.error.UnknownAccountException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Criação, consulta, listagem e remoção de links (RF04, RF06, RF07 e RF10; ADR-0001, §2.2, §2.3, D3 e D5). */
@Service
public class LinkService {

    public static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";
    public static final String ALIAS_TAKEN = "ALIAS_TAKEN";
    public static final String LINK_QUOTA_EXCEEDED = "LINK_QUOTA_EXCEEDED";
    public static final String CODE_GENERATION_EXHAUSTED = "CODE_GENERATION_EXHAUSTED";

    /** Tentativas de código gerado antes do 503 (D3). */
    static final int MAX_CODE_ATTEMPTS = 5;
    /** Um link precisa valer pelo menos isso (D5). */
    static final Duration MIN_TTL = Duration.ofMinutes(1);

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository links;
    private final ShortCodeGenerator codes;
    private final LinkProperties properties;
    private final HostClassifier hosts;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Counter created;
    private final Counter collisions;
    private final Counter exhausted;

    public LinkService(LinkRepository links, ShortCodeGenerator codes, LinkProperties properties, AppProperties app,
                       TransactionTemplate transaction, ApplicationEventPublisher events, MeterRegistry meters,
                       Clock clock) {
        this.links = links;
        this.codes = codes;
        this.properties = properties;
        this.hosts = new HostClassifier(app.baseUrl().getHost());
        this.transaction = transaction;
        this.events = events;
        this.clock = clock;
        this.created = meters.counter("shortener.links.created");
        this.collisions = meters.counter("shortener.code.collisions");
        this.exhausted = meters.counter("shortener.code.exhausted");
    }

    /**
     * Valida tudo antes de abrir a transação, para que uma requisição inválida não ocupe conexão do pool, e devolve
     * todos os campos inválidos de uma vez.
     *
     * @param expiresAt vazio para a validade padrão ({@code app.links.default-ttl})
     */
    public LinkView create(long ownerId, String url, @Nullable String alias, @Nullable Instant expiresAt) {
        // Precisão do timestamptz: a resposta mostra exatamente os instantes gravados.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        NewLink link = validate(url, alias, expiresAt, now);
        Inserted inserted;
        try {
            inserted = transaction.execute(status -> insert(ownerId, link, now));
        } catch (DataIntegrityViolationException ex) {
            // A única chave estrangeira é a do dono: o token é de uma conta que não existe mais.
            if (ex.getMostSpecificCause() instanceof SQLException sql
                    && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                throw new UnknownAccountException();
            }
            throw ex;
        }
        created.increment();
        log.info("Link criado (userId={}, linkId={}, code={}, host={})", ownerId, inserted.id(), inserted.code(),
                link.target().host());
        return new LinkView(inserted.code(), link.target().value(), LinkView.Status.ACTIVE, now, link.expiresAt());
    }

    /** Inclui links expirados (com status {@code EXPIRED}); os removidos não aparecem. */
    public Page<LinkView> list(long ownerId, int page, int size) {
        Instant now = clock.instant();
        return links.findByOwnerIdAndStatus(ownerId, LinkStatus.ACTIVE, PageRequest.of(page, size, NEWEST_FIRST))
                .map(link -> LinkView.of(link, now));
    }

    public LinkView get(long ownerId, String code) {
        if (!ShortCode.isWellFormed(code)) {
            throw notFound();
        }
        return links.findByCodeAndOwnerIdAndStatus(code, ownerId, LinkStatus.ACTIVE)
                .map(link -> LinkView.of(link, clock.instant()))
                .orElseThrow(LinkService::notFound);
    }

    /** Remove um link próprio, ativo ou expirado. Um link removido some da API e o redirecionamento devolve 410. */
    @Transactional
    public void delete(long ownerId, String code) {
        if (!ShortCode.isWellFormed(code) || links.markDeleted(code, ownerId, clock.instant()) == 0) {
            throw notFound();
        }
        events.publishEvent(new LinkDeleted(code));
        log.info("Link removido (userId={}, code={})", ownerId, code);
    }

    private Inserted insert(long ownerId, NewLink link, Instant now) {
        links.lockOwner(ownerId);
        if (links.countActive(ownerId, now) >= properties.maxActivePerUser()) {
            throw new ApiException(HttpStatus.CONFLICT, LINK_QUOTA_EXCEEDED, ("Você atingiu o limite de %d links "
                    + "ativos. Remova um link ou aguarde algum expirar.").formatted(properties.maxActivePerUser()));
        }
        if (link.alias() != null) {
            String alias = link.alias().value();
            return tryInsert(ownerId, alias, true, link, now).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                    ALIAS_TAKEN, "Este alias já está em uso."));
        }
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            Optional<Inserted> inserted = tryInsert(ownerId, codes.next(), false, link, now);
            if (inserted.isPresent()) {
                return inserted.get();
            }
            collisions.increment();
        }
        exhausted.increment();
        log.error("Nenhum código livre em {} tentativas: o espaço de códigos está saturado, aumente "
                + "app.links.code-length (atual: {})", MAX_CODE_ATTEMPTS, properties.codeLength());
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, CODE_GENERATION_EXHAUSTED,
                "Não foi possível gerar um código curto agora. Tente novamente em instantes.");
    }

    private Optional<Inserted> tryInsert(long ownerId, String code, boolean customAlias, NewLink link, Instant now) {
        return links.insertIfCodeAvailable(code, link.target().value(), ownerId, customAlias, now, link.expiresAt())
                .map(id -> new Inserted(id, code));
    }

    private NewLink validate(String url, @Nullable String alias, @Nullable Instant expiresAt, Instant now) {
        List<FieldViolation> violations = new ArrayList<>();
        TargetUrl target = check("url", violations, () -> TargetUrl.parse(url, hosts));
        Alias chosen = alias == null ? null : check("alias", violations, () -> new Alias(alias));
        Instant expiry = check("expiresAt", violations, () -> resolveExpiry(expiresAt, now));
        if (!violations.isEmpty()) {
            throw new InvalidFieldsException(violations);
        }
        return new NewLink(target, chosen, expiry);
    }

    /** Todo link expira: limita armazenamento e abuso (D5). */
    private Instant resolveExpiry(@Nullable Instant requested, Instant now) {
        if (requested == null) {
            return now.plus(properties.defaultTtl());
        }
        if (requested.isBefore(now.plus(MIN_TTL))) {
            throw new InvalidValueException("deve estar pelo menos 1 minuto no futuro");
        }
        if (requested.isAfter(now.plus(properties.maxTtl()))) {
            throw new InvalidValueException("deve estar no máximo %d dias no futuro"
                    .formatted(properties.maxTtl().toDays()));
        }
        return requested.truncatedTo(ChronoUnit.MICROS);
    }

    private static <T> @Nullable T check(String field, List<FieldViolation> violations, Supplier<T> rule) {
        try {
            return rule.get();
        } catch (InvalidValueException ex) {
            violations.add(new FieldViolation(field, ex.getMessage()));
            return null;
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, LINK_NOT_FOUND, "Link não encontrado.");
    }

    private record NewLink(TargetUrl target, @Nullable Alias alias, Instant expiresAt) {
    }

    private record Inserted(long id, String code) {
    }
}
