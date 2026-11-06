package br.com.encurtador.auth;

import br.com.encurtador.auth.support.AuthServerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O script de importação copia as credenciais do app com o id como subject e pode rodar de novo (ADR-0003, D8). */
@AuthServerTest
class ImportScriptTest {

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void dropSourceTables() {
        jdbc.sql("drop table if exists public.users").update();
        jdbc.sql("drop table if exists public.accounts").update();
    }

    @Test
    void importsTheUsersOfTheAppOnceAndKeepsTheirHashes() throws Exception {
        String email = "importado-" + UUID.randomUUID() + "@exemplo.com";
        jdbc.sql("""
                create table public.users (id bigint primary key, email varchar(254), password_hash varchar(255),
                    created_at timestamptz not null, updated_at timestamptz not null)""").update();
        jdbc.sql("insert into public.users values (424242, :email, '{bcrypt}hash', now(), now())")
                .param("email", email).update();

        runScript();
        runScript();

        assertThat(jdbc.sql("select subject, password_hash from credentials where email = :email")
                .param("email", email).query((rs, row) -> rs.getString(1) + " " + rs.getString(2)).list())
                .containsExactly("424242 {bcrypt}hash");
    }

    @Test
    void readsTheAccountsTableWhenTheAppAlreadyRenamedIt() throws Exception {
        String email = "renomeado-" + UUID.randomUUID() + "@exemplo.com";
        jdbc.sql("""
                create table public.accounts (id bigint primary key, email varchar(254), password_hash varchar(255),
                    created_at timestamptz not null, updated_at timestamptz not null)""").update();
        jdbc.sql("insert into public.accounts values (434343, :email, '{bcrypt}hash', now(), now())")
                .param("email", email).update();

        runScript();

        assertThat(jdbc.sql("select subject from credentials where email = :email").param("email", email)
                .query(String.class).single()).isEqualTo("434343");
    }

    @Test
    void withoutSourceTableItDoesNothing() throws Exception {
        long before = jdbc.sql("select count(*) from credentials").query(Long.class).single();

        runScript();

        assertThat(jdbc.sql("select count(*) from credentials").query(Long.class).single()).isEqualTo(before);
    }

    private void runScript() throws Exception {
        String script = Files.readString(Path.of("scripts/importar-usuarios.sql"));
        jdbc.sql(script).update();
    }
}
