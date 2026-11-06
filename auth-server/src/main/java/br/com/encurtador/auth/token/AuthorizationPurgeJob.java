package br.com.encurtador.auth.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Apaga as autorizações cujos códigos e tokens já expiraram todos (ADR-0003, D5 e R10): a tabela guarda os valores
 * em claro, e uma autorização vencida não serve para mais nada.
 */
@Component
class AuthorizationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationPurgeJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    AuthorizationPurgeJob(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.auth.purge-cron:0 30 3 * * *}")
    int purge() {
        int deleted = jdbc.sql("""
                        delete from oauth2_authorization
                        where greatest(authorization_code_expires_at, access_token_expires_at, oidc_id_token_expires_at,
                                       refresh_token_expires_at, user_code_expires_at, device_code_expires_at) < :now""")
                .param("now", Timestamp.from(clock.instant()))
                .update();
        log.info("Expurgo de autorizações vencidas: {} removidas", deleted);
        return deleted;
    }
}
