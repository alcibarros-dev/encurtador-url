package br.com.encurtador.identity.infra;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Optional;

import static br.com.encurtador.shared.time.SqlTimestamps.toSql;

/** Implementação do fragmento {@link UserInsertion}, encontrada pelo Spring Data pelo sufixo {@code Impl}. */
class UserInsertionImpl implements UserInsertion {

    private final JdbcClient jdbc;

    UserInsertionImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> insertIfEmailAvailable(String email, String passwordHash, Instant now) {
        return jdbc.sql("""
                        insert into users (email, password_hash, created_at, updated_at)
                        values (:email, :passwordHash, :now, :now)
                        on conflict (email) do nothing
                        returning id
                        """)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("now", toSql(now))
                .query(Long.class)
                .optional();
    }
}
