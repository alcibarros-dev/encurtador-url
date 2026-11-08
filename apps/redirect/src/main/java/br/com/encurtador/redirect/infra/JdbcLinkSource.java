package br.com.encurtador.redirect.infra;

import br.com.encurtador.redirect.application.LinkSource;
import br.com.encurtador.redirect.application.StoredLink;
import br.com.encurtador.shared.time.SqlTimestamps;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Lê a tabela {@code links}, cujo schema e migrações são da API (ADR-0006, D2). Uma consulta pelo índice único do
 * código, sem transação: o pool é só de leitura, e o PostgreSQL recusa qualquer escrita por ele.
 * <p>
 * Estas quatro colunas são o contrato com a API: mudar uma delas exige migração compatível com as duas aplicações.
 */
@Component
class JdbcLinkSource implements LinkSource {

    private final JdbcClient jdbc;

    JdbcLinkSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredLink> findByCode(String code) {
        return jdbc.sql("select id, target_url, status, expires_at from links where code = :code")
                .param("code", code)
                .query((rs, row) -> new StoredLink(rs.getLong("id"), rs.getString("target_url"),
                        "DELETED".equals(rs.getString("status")),
                        SqlTimestamps.fromSql(rs, "expires_at")))
                .optional();
    }
}
