package br.com.encurtador.link.infra;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Optional;

import static br.com.encurtador.shared.time.SqlTimestamps.toSql;

/** Implementação do fragmento {@link LinkWrites}, encontrada pelo Spring Data pelo sufixo {@code Impl}. */
class LinkWritesImpl implements LinkWrites {

    private final JdbcClient jdbc;

    LinkWritesImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockOwner(long ownerId) {
        // Liberado sozinho no commit ou rollback. É o único advisory lock da aplicação: se surgir outro, migrar para
        // a forma de duas chaves (namespace, id).
        jdbc.sql("select pg_advisory_xact_lock(:ownerId)")
                .param("ownerId", ownerId)
                .query(rs -> {
                });
    }

    @Override
    public long countActive(long ownerId, Instant now) {
        return jdbc.sql("""
                        select count(*) from links
                         where owner_id = :ownerId and status = 'ACTIVE' and expires_at > :now
                        """)
                .param("ownerId", ownerId)
                .param("now", toSql(now))
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Long> insertIfCodeAvailable(String code, String targetUrl, long ownerId, boolean customAlias,
                                                Instant createdAt, Instant expiresAt) {
        return jdbc.sql("""
                        insert into links (code, target_url, owner_id, custom_alias, status, created_at, expires_at)
                        values (:code, :targetUrl, :ownerId, :customAlias, 'ACTIVE', :createdAt, :expiresAt)
                        on conflict (code) do nothing
                        returning id
                        """)
                .param("code", code)
                .param("targetUrl", targetUrl)
                .param("ownerId", ownerId)
                .param("customAlias", customAlias)
                .param("createdAt", toSql(createdAt))
                .param("expiresAt", toSql(expiresAt))
                .query(Long.class)
                .optional();
    }

    @Override
    public int markDeleted(String code, long ownerId, Instant now) {
        return jdbc.sql("""
                        update links set status = 'DELETED', deleted_at = :now
                         where code = :code and owner_id = :ownerId and status = 'ACTIVE'
                        """)
                .param("code", code)
                .param("ownerId", ownerId)
                .param("now", toSql(now))
                .update();
    }
}
