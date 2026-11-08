package br.com.encurtador.support;


import br.com.authcore.security.UserIdResolver;
import java.util.Map;
import java.util.UUID;

/**
 * Contas dos testes de integração (ADR-0003, D12): um {@code sub} novo passa pelo mesmo provisionamento do primeiro
 * acesso de um usuário real, e o token sai do {@link TestTokens}. Nenhum teste cadastra ou faz login pela API.
 */
public final class TestAccounts {

    private final UserIdResolver resolver;
    private final TestTokens tokens;

    TestAccounts(UserIdResolver resolver, TestTokens tokens) {
        this.resolver = resolver;
        this.tokens = tokens;
    }

    /** Conta já criada no app, com um {@code sub} aleatório como o UUID do auth-server. */
    public TestAccount create() {
        String subject = UUID.randomUUID().toString();
        return new TestAccount(resolver.resolve(subject, Map.of()), subject);
    }

    /** Token de um {@code sub} qualquer, sem criar a conta: o app a cria no primeiro acesso. */
    public String token(String subject) {
        return tokens.accessToken(subject);
    }

    public final class TestAccount {

        private final long id;
        private final String subject;

        private TestAccount(long id, String subject) {
            this.id = id;
            this.subject = subject;
        }

        public long id() {
            return id;
        }

        public String subject() {
            return subject;
        }

        /** Token novo, emitido agora pelo relógio da aplicação. */
        public String token() {
            return tokens.accessToken(subject);
        }
    }
}
