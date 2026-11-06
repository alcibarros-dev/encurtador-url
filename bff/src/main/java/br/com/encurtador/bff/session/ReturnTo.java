package br.com.encurtador.bff.session;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Volta do login (ADR-0004, D4): só caminhos das rotas logadas da SPA (D6), começando com {@code /} e sem {@code //}
 * nem {@code /\}; qualquer outra coisa volta para {@code /links}. A SPA aplica a mesma regra.
 */
public final class ReturnTo {

    public static final String DEFAULT = "/links";
    /** Atributo da sessão onde o destino fica entre o início do login e a volta do auth-server. */
    static final String SESSION_ATTRIBUTE = ReturnTo.class.getName();

    private static final List<Pattern> SPA_ROUTES = List.of(
            Pattern.compile("/links(/[A-Za-z0-9_-]+)?"),
            Pattern.compile("/estatisticas"),
            Pattern.compile("/conta"));
    /** Query da própria SPA, sem nada que mude o destino. */
    private static final Pattern QUERY = Pattern.compile("[A-Za-z0-9_=&%.-]*");

    private ReturnTo() {
    }

    public static String sanitize(@Nullable String candidate) {
        if (candidate == null || !candidate.startsWith("/") || candidate.startsWith("//")
                || candidate.startsWith("/\\")) {
            return DEFAULT;
        }
        int queryStart = candidate.indexOf('?');
        String path = queryStart < 0 ? candidate : candidate.substring(0, queryStart);
        String query = queryStart < 0 ? "" : candidate.substring(queryStart + 1);
        boolean knownRoute = SPA_ROUTES.stream().anyMatch(route -> route.matcher(path).matches());
        return knownRoute && QUERY.matcher(query).matches() ? candidate : DEFAULT;
    }
}
