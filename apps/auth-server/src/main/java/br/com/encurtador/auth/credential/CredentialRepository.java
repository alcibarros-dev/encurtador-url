package br.com.encurtador.auth.credential;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Acesso à tabela {@code credentials} do schema {@code auth} (ADR-0003, D8). */
@Repository
public class CredentialRepository {

    private final JdbcClient jdbc;

    public CredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Credential> findByEmail(String email) {
        return jdbc.sql("select id, subject, email, password_hash from credentials where email = :email")
                .param("email", email)
                .query(Credential.class)
                .optional();
    }

    /** @return a credencial criada, ou vazio se o e-mail já tem conta */
    public Optional<Credential> insertIfEmailAvailable(String subject, String email, String passwordHash, Instant now) {
        return jdbc.sql("""
                        insert into credentials (subject, email, password_hash, created_at, updated_at)
                        values (:subject, :email, :hash, :now, :now)
                        on conflict (email) do nothing
                        returning id, subject, email, password_hash""")
                .param("subject", subject)
                .param("email", email)
                .param("hash", passwordHash)
                .param("now", Timestamp.from(now))
                .query(Credential.class)
                .optional();
    }

    public void updatePasswordHash(String email, String passwordHash, Instant now) {
        jdbc.sql("update credentials set password_hash = :hash, updated_at = :now where email = :email")
                .param("hash", passwordHash)
                .param("now", Timestamp.from(now))
                .param("email", email)
                .update();
    }
}
