package br.com.encurtador.identity.infra;

import br.com.encurtador.identity.application.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expurgo diário dos refresh tokens expirados (ADR-0001, D9). O horário vem de {@code app.refresh-token.purge-cron}.
 */
@Component
class RefreshTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurgeJob.class);

    private final RefreshTokenService refreshTokens;

    RefreshTokenPurgeJob(RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Scheduled(cron = "${app.refresh-token.purge-cron}", zone = "UTC")
    void purge() {
        int deleted = refreshTokens.purgeExpired();
        log.info("Expurgo de refresh tokens concluído: {} removidos", deleted);
    }
}
