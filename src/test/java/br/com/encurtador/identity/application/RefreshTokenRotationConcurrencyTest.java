package br.com.encurtador.identity.application;

import br.com.encurtador.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rotações simultâneas do mesmo refresh token, com transações reais em paralelo (ADR-0001, D14, cenário 12).
 */
@IntegrationTest
class RefreshTokenRotationConcurrencyTest {

    private static final int ATTEMPTS = 8;

    @Autowired
    RegistrationService registration;

    @Autowired
    RefreshTokenService refreshTokens;

    @Test
    void exactlyOneConcurrentRotationSucceedsAndTheOthersRevokeTheSession() throws Exception {
        long userId = registration.register("concorrencia-" + UUID.randomUUID() + "@exemplo.com", "senha-segura-1").id();
        String token = refreshTokens.startSession(userId).value();

        List<RefreshOutcome> outcomes = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(ATTEMPTS)) {
            List<Future<RefreshOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < ATTEMPTS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return refreshTokens.rotate(token);
                }));
            }
            start.countDown();
            for (Future<RefreshOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(outcomes).filteredOn(RefreshOutcome.Issued.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(RefreshOutcome.Reused.class::isInstance).hasSize(ATTEMPTS - 1);

        // Quem venceu também perde a sessão: o reuso revogou a família inteira, inclusive o token novo.
        RefreshOutcome.Issued winner = outcomes.stream()
                .filter(RefreshOutcome.Issued.class::isInstance)
                .map(RefreshOutcome.Issued.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(refreshTokens.rotate(winner.refreshToken().value())).isInstanceOf(RefreshOutcome.Reused.class);
    }
}
