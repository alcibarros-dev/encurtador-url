package br.com.encurtador.auth.token;

import br.com.encurtador.auth.support.AuthServerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Autorizações vencidas somem; as que ainda têm algum token válido ficam (ADR-0003, D5 e R10). */
@AuthServerTest
class AuthorizationPurgeJobTest {

    @Autowired
    AuthorizationPurgeJob job;

    @Autowired
    JdbcClient jdbc;

    @Test
    void removesOnlyAuthorizationsWhoseTokensAllExpired() {
        Instant now = Instant.now();
        String expired = insert(now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
        String refreshStillValid = insert(now.minus(1, ChronoUnit.HOURS), now.plus(10, ChronoUnit.DAYS));

        job.purge();

        assertThat(exists(expired)).isFalse();
        assertThat(exists(refreshStillValid)).isTrue();
    }

    private String insert(Instant accessExpiresAt, Instant refreshExpiresAt) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        insert into oauth2_authorization (id, registered_client_id, principal_name,
                            authorization_grant_type, access_token_expires_at, refresh_token_expires_at)
                        values (:id, 'cliente', 'fulano@exemplo.com', 'authorization_code', :access, :refresh)""")
                .param("id", id)
                .param("access", Timestamp.from(accessExpiresAt))
                .param("refresh", Timestamp.from(refreshExpiresAt))
                .update();
        return id;
    }

    private boolean exists(String id) {
        return jdbc.sql("select count(*) from oauth2_authorization where id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }
}
