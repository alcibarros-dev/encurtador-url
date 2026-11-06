package br.com.encurtador.link.application;

import br.com.encurtador.link.LinkDeleted;
import br.com.encurtador.shared.error.ApiException;
import br.com.encurtador.support.IntegrationTest;
import br.com.encurtador.support.MutableClock;
import br.com.encurtador.support.TestAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cota, unicidade e evento de remoção com transações reais (ADR-0001, D3 e D5; cenários 11 e 13 do D14). Os cenários
 * de concorrência disparam as criações juntas, em threads próprias.
 */
@IntegrationTest
@RecordApplicationEvents
class LinkServiceIntegrationTest {

    private static final String TARGET = "https://exemplo.com/";
    private static final int PARALLEL = 8;

    @Autowired
    LinkService links;

    @Autowired
    LinkProperties properties;

    @Autowired
    TestAccounts accounts;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationEvents events;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void quotaCountsOnlyLinksThatHaveNotExpired() {
        long owner = newUser();
        fillQuotaLeaving(owner, 1);
        links.create(owner, TARGET, null, clock.instant().plus(Duration.ofMinutes(2)));

        assertThatThrownBy(() -> links.create(owner, TARGET, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(LinkService.LINK_QUOTA_EXCEEDED);
                    assertThat(ex.getMessage()).contains(String.valueOf(properties.maxActivePerUser()));
                });

        clock.advance(Duration.ofMinutes(3));
        assertThat(links.create(owner, TARGET, null, null).code()).isNotBlank();
    }

    @Test
    void parallelCreationsWithOneSlotLeftCreateExactlyOneLink() throws Exception {
        long owner = newUser();
        fillQuotaLeaving(owner, 1);

        List<Outcome> outcomes = runTogether(PARALLEL, attempt -> links.create(owner, TARGET, null, null));

        assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> LinkService.LINK_QUOTA_EXCEEDED.equals(outcome.errorCode()))
                .hasSize(PARALLEL - 1);
        assertThat(links.list(owner, 0, 1).getTotalElements()).isEqualTo(properties.maxActivePerUser());
    }

    @Test
    void sameAliasRequestedInParallelByDifferentUsersIsCreatedOnce() throws Exception {
        String alias = "disputa-" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> owners = new ArrayList<>();
        for (int i = 0; i < PARALLEL; i++) {
            owners.add(newUser());
        }

        List<Outcome> outcomes = runTogether(PARALLEL, attempt -> links.create(owners.get(attempt), TARGET, alias, null));

        assertThat(outcomes).filteredOn(Outcome::created).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> LinkService.ALIAS_TAKEN.equals(outcome.errorCode()))
                .hasSize(PARALLEL - 1);
    }

    @Test
    void deletionPublishesLinkDeleted() {
        long owner = newUser();
        String code = links.create(owner, TARGET, null, null).code();

        links.delete(owner, code);

        assertThat(events.stream(LinkDeleted.class)).containsExactly(new LinkDeleted(code));
    }

    @Test
    void failedDeletionPublishesNothing() {
        long owner = newUser();
        String code = links.create(owner, TARGET, null, null).code();

        assertThatThrownBy(() -> links.delete(newUser(), code)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> links.delete(owner, "inexistente")).isInstanceOf(ApiException.class);

        assertThat(events.stream(LinkDeleted.class)).isEmpty();
    }

    private void fillQuotaLeaving(long owner, int free) {
        for (int i = 0; i < properties.maxActivePerUser() - free; i++) {
            links.create(owner, TARGET + i, null, null);
        }
    }

    private long newUser() {
        return accounts.create().id();
    }

    /** Executa {@code attempts} chamadas liberadas ao mesmo tempo e devolve o resultado de cada uma. */
    private static List<Outcome> runTogether(int attempts, Attempt attempt) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Outcome> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                int index = i;
                Callable<Outcome> call = () -> {
                    start.await();
                    try {
                        attempt.run(index);
                        return new Outcome(true, null);
                    } catch (ApiException ex) {
                        return new Outcome(false, ex.code());
                    }
                };
                futures.add(executor.submit(call));
            }
            start.countDown();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException ex) {
                    throw new AssertionError("Falha inesperada numa das chamadas paralelas", ex.getCause());
                }
            }
        }
        return outcomes;
    }

    @FunctionalInterface
    private interface Attempt {
        void run(int index);
    }

    private record Outcome(boolean created, String errorCode) {
    }
}
