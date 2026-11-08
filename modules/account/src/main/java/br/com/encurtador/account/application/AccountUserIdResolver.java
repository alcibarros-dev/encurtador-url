package br.com.encurtador.account.application;

import br.com.encurtador.account.infra.AccountRepository;
import dev.alcibarros.auth.security.UnknownSubjectException;
import dev.alcibarros.auth.security.UserIdResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code sub} do token → id local da conta (ADR-0003, D7). Um {@code sub} desconhecido cria a conta no primeiro
 * acesso. O {@code sub} é opaco: o app não depende do formato que o servidor de autorização escolheu.
 * <p>
 * O mapeamento não muda depois de criado, então fica num cache em memória de tamanho limitado. Remapear os
 * {@code subject} no banco, como na troca de provedor (ADR-0003, D13), exige reiniciar o app para descartá-lo.
 */
@Component
class AccountUserIdResolver implements UserIdResolver {

    /** Tamanho da coluna {@code accounts.subject}. */
    static final int MAX_SUBJECT_LENGTH = 255;
    static final int CACHE_SIZE = 10_000;

    private final AccountRepository accounts;
    private final Map<String, Long> cache;

    @Autowired
    AccountUserIdResolver(AccountRepository accounts) {
        this(accounts, CACHE_SIZE);
    }

    AccountUserIdResolver(AccountRepository accounts, int cacheSize) {
        this.accounts = accounts;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > cacheSize;
            }
        });
    }

    @Override
    public long resolve(String subject, Map<String, Object> claims) {
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new UnknownSubjectException("O token não tem um assunto válido.");
        }
        Long cached = cache.get(subject);
        if (cached != null) {
            return cached;
        }
        long id = accounts.findIdBySubject(subject).orElseGet(() -> accounts.provision(subject));
        cache.put(subject, id);
        return id;
    }
}
