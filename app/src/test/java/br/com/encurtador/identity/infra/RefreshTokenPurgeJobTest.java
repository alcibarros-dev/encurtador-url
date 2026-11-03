package br.com.encurtador.identity.infra;

import br.com.encurtador.identity.application.RefreshTokenService;
import br.com.encurtador.identity.application.RegistrationService;
import br.com.encurtador.identity.domain.RefreshToken;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class RefreshTokenPurgeJobTest {

    @Autowired
    RefreshTokenPurgeJob job;

    @Autowired
    RegistrationService registration;

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    RefreshTokenRepository repository;

    @Autowired
    MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void deletesOnlyTokensExpiredForLongerThanTheGracePeriod() {
        long userId = registration.register("expurgo-" + UUID.randomUUID() + "@exemplo.com", "senha-segura-1").id();
        RefreshToken expiredLongAgo = refreshTokens.startSession(userId);   // expira no dia 7
        clock.advance(Duration.ofDays(1));
        RefreshToken expiredRecently = refreshTokens.startSession(userId);  // expira no dia 8
        clock.advance(Duration.ofDays(7).plusHours(12));
        RefreshToken active = refreshTokens.startSession(userId);           // dia 8,5: ativo

        job.purge();

        assertThat(repository.findByHash(expiredLongAgo.hash())).isEmpty();
        assertThat(repository.findByHash(expiredRecently.hash())).isPresent();
        assertThat(repository.findByHash(active.hash())).isPresent();
    }
}
