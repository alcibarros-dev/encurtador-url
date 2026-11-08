package br.com.encurtador.support;

import br.com.encurtador.shared.time.SqlTimestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Grava links como a API grava, numa conexão própria com escrita: o pool da aplicação é só de leitura (ADR-0006, D2).
 * Cada link tem um dono novo e um código aleatório no formato do {@code ShortCode}.
 */
public final class TestLinks {

    private final JdbcClient jdbc;
    private final MutableClock clock;

    TestLinks(PostgreSQLContainer postgres, MutableClock clock) {
        this.jdbc = JdbcClient.create(new SimpleDriverDataSource(driverFor(postgres.getJdbcUrl()),
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        this.clock = clock;
    }

    /** Link ativo por 30 dias. */
    public Link active(String targetUrl) {
        return create(targetUrl, clock.instant().plus(Duration.ofDays(30)));
    }

    public Link create(String targetUrl, Instant expiresAt) {
        Instant now = clock.instant();
        long owner = jdbc.sql("""
                        insert into accounts (subject, created_at, updated_at) values (:subject, :now, :now)
                        returning id""")
                .param("subject", UUID.randomUUID().toString())
                .param("now", SqlTimestamps.toSql(now))
                .query(Long.class).single();
        String code = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        long id = jdbc.sql("""
                        insert into links (code, target_url, owner_id, custom_alias, status, created_at, expires_at)
                        values (:code, :target, :owner, false, 'ACTIVE', :now, :expiresAt)
                        returning id""")
                .param("code", code)
                .param("target", targetUrl)
                .param("owner", owner)
                .param("now", SqlTimestamps.toSql(now.minusSeconds(1)))
                .param("expiresAt", SqlTimestamps.toSql(expiresAt))
                .query(Long.class).single();
        return new Link(id, code);
    }

    /** Remoção como a da API, sem o tombstone: quem o grava é a API, depois do commit (D4). */
    public void markDeleted(String code) {
        jdbc.sql("update links set status = 'DELETED', deleted_at = :now where code = :code")
                .param("now", SqlTimestamps.toSql(clock.instant()))
                .param("code", code)
                .update();
    }

    public void changeTarget(String code, String targetUrl) {
        jdbc.sql("update links set target_url = :target where code = :code")
                .param("target", targetUrl)
                .param("code", code)
                .update();
    }

    private static Driver driverFor(String url) {
        try {
            return DriverManager.getDriver(url);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record Link(long id, String code) {
    }
}
