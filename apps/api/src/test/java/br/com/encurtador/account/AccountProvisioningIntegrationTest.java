package br.com.encurtador.account;

import br.com.authcore.security.UserIdResolver;
import br.com.encurtador.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Contas criadas no primeiro acesso, com o banco real (ADR-0003, D7 e D12). */
@IntegrationTest
class AccountProvisioningIntegrationTest {

    private static final int PARALLEL = 16;

    @Autowired
    UserIdResolver accounts;

    @Autowired
    JdbcClient jdbc;

    @Test
    void unknownSubjectCreatesAnAccountWithOnlySubjectAndDates() {
        String subject = UUID.randomUUID().toString();

        long id = accounts.resolve(subject, Map.of());

        var row = jdbc.sql("select * from accounts where id = ?").param(id).query().singleRow();
        // Depois da V5, o app não guarda e-mail nem senha (ADR-0003, D7 e D8).
        assertThat(row).containsOnlyKeys("id", "subject", "created_at", "updated_at");
        assertThat(row.get("subject")).isEqualTo(subject);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(accounts.resolve(subject, Map.of())).isEqualTo(id);
    }

    @Test
    void parallelFirstAccessesOfTheSameSubjectCreateASingleAccount() throws Exception {
        String subject = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> ids = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL)) {
            for (int i = 0; i < PARALLEL; i++) {
                ids.add(executor.submit(() -> {
                    start.await();
                    return accounts.resolve(subject, Map.of());
                }));
            }
            start.countDown();
            List<Long> resolved = new ArrayList<>();
            for (Future<Long> id : ids) {
                resolved.add(id.get());
            }
            assertThat(resolved).containsOnly(resolved.getFirst());
        }

        assertThat(jdbc.sql("select count(*) from accounts where subject = ?").param(subject).query(Long.class)
                .single()).isEqualTo(1);
    }

    /** Usuário migrado (ADR-0003, D8): o subject é o id antigo em texto, e o id da conta, com os links, não muda. */
    @Test
    void migratedUserKeepsTheOldId() {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        long id = jdbc.sql("""
                        insert into accounts (subject, created_at, updated_at)
                        values (:subject, :now, :now) returning id""")
                .param("subject", "provisorio-" + UUID.randomUUID())
                .param("now", now)
                .query(Long.class).single();
        jdbc.sql("update accounts set subject = id::text where id = ?").param(id).update();

        assertThat(accounts.resolve(Long.toString(id), Map.of())).isEqualTo(id);
    }
}
