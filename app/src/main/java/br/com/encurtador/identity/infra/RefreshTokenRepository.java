package br.com.encurtador.identity.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static br.com.encurtador.shared.time.SqlTimestamps.fromSql;
import static br.com.encurtador.shared.time.SqlTimestamps.toSql;

/**
 * Refresh tokens em SQL explícito: a rotação depende de {@code UPDATE … RETURNING} atômico (ADR-0001, D6 e D9).
 * Sempre recebe o hash, nunca o token em claro.
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, UUID familyId, String tokenHash, Instant expiresAt, Instant familyExpiresAt,
                       Instant now) {
        jdbc.sql("""
                        insert into refresh_tokens
                            (user_id, family_id, token_hash, expires_at, family_expires_at, created_at)
                        values (:userId, :familyId, :tokenHash, :expiresAt, :familyExpiresAt, :now)
                        """)
                .param("userId", userId)
                .param("familyId", familyId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", toSql(expiresAt))
                .param("familyExpiresAt", toSql(familyExpiresAt))
                .param("now", toSql(now))
                .update();
    }

    /**
     * Revoga o token se ele ainda estiver ativo e devolve a sessão a que pertence. Com dois pedidos simultâneos,
     * o segundo espera o lock da linha e não a encontra mais ativa: só um consegue rotacionar.
     */
    public Optional<Session> revokeIfActive(String tokenHash, Instant now) {
        return jdbc.sql("""
                        update refresh_tokens set revoked_at = :now
                         where token_hash = :tokenHash and revoked_at is null and expires_at > :now
                        returning user_id, family_id, family_expires_at
                        """)
                .param("tokenHash", tokenHash)
                .param("now", toSql(now))
                .query((rs, row) -> new Session(
                        rs.getLong("user_id"), rs.getObject("family_id", UUID.class), fromSql(rs, "family_expires_at")))
                .optional();
    }

    public Optional<StoredToken> findByHash(String tokenHash) {
        return jdbc.sql("""
                        select user_id, family_id, expires_at, revoked_at
                          from refresh_tokens
                         where token_hash = :tokenHash
                        """)
                .param("tokenHash", tokenHash)
                .query((rs, row) -> new StoredToken(rs.getLong("user_id"), rs.getObject("family_id", UUID.class),
                        fromSql(rs, "expires_at"), fromSql(rs, "revoked_at")))
                .optional();
    }

    public int revokeFamily(UUID familyId, Instant now) {
        return jdbc.sql("update refresh_tokens set revoked_at = :now where family_id = :familyId and revoked_at is null")
                .param("familyId", familyId)
                .param("now", toSql(now))
                .update();
    }

    /** Revoga a sessão inteira do token apresentado, seja ele o atual ou um já rotacionado. */
    public int revokeFamilyOf(String tokenHash, Instant now) {
        return jdbc.sql("""
                        update refresh_tokens set revoked_at = :now
                         where revoked_at is null
                           and family_id = (select family_id from refresh_tokens where token_hash = :tokenHash)
                        """)
                .param("tokenHash", tokenHash)
                .param("now", toSql(now))
                .update();
    }

    public int revokeAllOfUser(long userId, Instant now) {
        return jdbc.sql("update refresh_tokens set revoked_at = :now where user_id = :userId and revoked_at is null")
                .param("userId", userId)
                .param("now", toSql(now))
                .update();
    }

    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from refresh_tokens where expires_at < :cutoff")
                .param("cutoff", toSql(cutoff))
                .update();
    }

    /** Sessão (família) de um token recém-revogado pela rotação. */
    public record Session(long userId, UUID familyId, Instant expiresAt) {
    }

    public record StoredToken(long userId, UUID familyId, Instant expiresAt, Instant revokedAt) {
    }
}
