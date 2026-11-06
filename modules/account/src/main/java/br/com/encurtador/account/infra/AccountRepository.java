package br.com.encurtador.account.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** Tabela {@code accounts}: só o {@code subject} e as datas (ADR-0003, D7 e D8). */
@Repository
public class AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountRepository.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<Long> findIdBySubject(String subject) {
        return jdbc.sql("select id from accounts where subject = :subject")
                .param("subject", subject)
                .query(Long.class)
                .optional();
    }

    /**
     * Cria a conta se ainda não existir e devolve o id dela. Acessos simultâneos do mesmo {@code subject} criam uma
     * conta só: quem perde a disputa não insere nada e lê a conta de quem ganhou.
     * <p>
     * São dois comandos, e não um só com CTE: no READ COMMITTED, a leitura de um mesmo comando usa a foto tirada antes
     * de o {@code INSERT} esperar pela outra transação e não veria a conta que ela gravou.
     */
    public long provision(String subject) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int inserted = jdbc.sql("""
                        insert into accounts (subject, created_at, updated_at)
                        values (:subject, :now, :now)
                        on conflict (subject) do nothing""")
                .param("subject", subject)
                .param("now", now)
                .update();
        long id = findIdBySubject(subject)
                .orElseThrow(() -> new IllegalStateException("Conta não encontrada logo depois de criada."));
        if (inserted == 1) {
            log.info("Conta criada no primeiro acesso (accountId={})", id);
        }
        return id;
    }
}
