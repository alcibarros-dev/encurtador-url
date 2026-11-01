package br.com.encurtador.redirect.infra;

import br.com.encurtador.link.LinkDeleted;
import br.com.encurtador.redirect.application.LinkCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Grava o tombstone de remoção depois do commit (ADR-0001, §2.3 e D7; corrige P1 e P13). Remoção desfeita não toca o
 * cache.
 * <p>
 * É {@code SET} incondicional, e o preenchimento é {@code SET NX}: nenhuma ordem entre leitor e remoção reinstala a
 * entrada ativa. Se a escrita falhar, a defasagem máxima é {@code app.cache.max-ttl}.
 */
@Component
class LinkCacheInvalidator {

    private final LinkCache cache;

    LinkCacheInvalidator(LinkCache cache) {
        this.cache = cache;
    }

    /**
     * Roda no {@code afterCompletion}, onde o Spring só loga exceções. O {@link LinkCache} não lança: a falha vira a
     * métrica {@code shortener.cache.errors{op=invalidate}}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onLinkDeleted(LinkDeleted event) {
        cache.markDeleted(event.code());
    }
}
