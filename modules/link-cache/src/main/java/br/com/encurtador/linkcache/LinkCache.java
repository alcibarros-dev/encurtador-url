package br.com.encurtador.linkcache;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;

/**
 * Cache de resolução. Nenhuma operação lança exceção: com o Redis fora, a leitura diz por quê e as escritas são
 * perdidas (ADR-0001, D7).
 */
public interface LinkCache {

    Read read(String code);

    /** {@code SET NX}: nunca sobrescreve, para que um preenchimento atrasado não apague um tombstone de remoção. */
    void putIfAbsent(String code, CachedLink entry, Duration ttl);

    /** Tombstone incondicional, gravado depois do commit da remoção. */
    void markDeleted(String code);

    /** Resultado de uma leitura; {@link #entry()} só existe em {@link Outcome#HIT}. */
    record Read(Outcome outcome, @Nullable CachedLink entry) {

        public static final Read MISS = new Read(Outcome.MISS, null);
        public static final Read ERROR = new Read(Outcome.ERROR, null);
        public static final Read BYPASSED = new Read(Outcome.BYPASSED, null);

        public static Read hit(CachedLink entry) {
            return new Read(Outcome.HIT, entry);
        }
    }

    /** Valores da tag {@code cache} da métrica {@code shortener.redirect} (D13). */
    enum Outcome {
        HIT,
        MISS,
        /** O Redis foi chamado e falhou, ou devolveu algo ilegível. */
        ERROR,
        /** O disjuntor estava aberto: o Redis nem foi chamado. */
        BYPASSED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
