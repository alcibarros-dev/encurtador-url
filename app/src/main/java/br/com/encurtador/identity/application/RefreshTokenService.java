package br.com.encurtador.identity.application;

import br.com.encurtador.identity.domain.RefreshToken;
import br.com.encurtador.identity.infra.RefreshTokenRepository;
import br.com.encurtador.identity.infra.RefreshTokenRepository.Session;
import br.com.encurtador.identity.infra.RefreshTokenRepository.StoredToken;
import br.com.encurtador.shared.time.UuidV7Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessões de refresh token: emissão no login, rotação com detecção de reuso, encerramento e expurgo
 * (ADR-0001, D9).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final RefreshTokenProperties properties;
    private final UuidV7Generator ids;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenProperties properties, UuidV7Generator ids,
                               Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    /** Abre uma sessão nova; ela dura no máximo {@code sessionTtl}, por mais que seja renovada. */
    public RefreshToken startSession(long userId) {
        Instant now = clock.instant();
        return issue(userId, ids.next(), now.plus(properties.sessionTtl()), now);
    }

    @Transactional
    public RefreshOutcome rotate(String presentedToken) {
        Instant now = clock.instant();
        String hash = RefreshToken.hashOf(presentedToken);

        Optional<Session> session = tokens.revokeIfActive(hash, now);
        if (session.isPresent()) {
            Session active = session.get();
            return new RefreshOutcome.Issued(active.userId(),
                    issue(active.userId(), active.familyId(), active.expiresAt(), now));
        }

        Optional<StoredToken> stored = tokens.findByHash(hash);
        if (stored.isEmpty() || !stored.get().expiresAt().isAfter(now)) {
            return RefreshOutcome.INVALID;
        }
        // Existe, não expirou e não estava ativo: já foi rotacionado. Quem o reapresenta pode tê-lo roubado.
        StoredToken reused = stored.get();
        tokens.revokeFamily(reused.familyId(), now);
        log.warn("Reuso de refresh token: sessão revogada (userId={}, familyId={})", reused.userId(), reused.familyId());
        return new RefreshOutcome.Reused(reused.userId(), reused.familyId());
    }

    /** Logout: revoga a sessão do token apresentado. Idempotente; token desconhecido não é erro. */
    public void endSession(String presentedToken) {
        tokens.revokeFamilyOf(RefreshToken.hashOf(presentedToken), clock.instant());
    }

    /** Revoga todas as sessões do usuário. Participa da transação de quem chama (troca de senha). */
    public void endAllSessions(long userId) {
        tokens.revokeAllOfUser(userId, clock.instant());
    }

    public int purgeExpired() {
        return tokens.deleteExpiredBefore(clock.instant().minus(properties.purgeGrace()));
    }

    private RefreshToken issue(long userId, UUID familyId, Instant sessionExpiresAt, Instant now) {
        RefreshToken token = RefreshToken.generate();
        Instant expiresAt = min(now.plus(properties.ttl()), sessionExpiresAt);
        tokens.insert(userId, familyId, token.hash(), expiresAt, sessionExpiresAt, now);
        return token;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
