package br.com.encurtador.link.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Código curto: identificador público e permanente de um link, gerado ou escolhido como alias (ADR-0001, D3 e D4).
 * Diferencia maiúsculas de minúsculas.
 */
public final class ShortCode {

    public static final int MAX_LENGTH = 32;

    /** Forma de todo código; também é o padrão da rota de redirecionamento. */
    public static final String PATTERN = "[A-Za-z0-9_-]{4,32}";

    private static final Pattern WELL_FORMED = Pattern.compile(PATTERN);

    /** Caminhos da aplicação e nomes que confundiriam quem recebe o link; comparados sem diferenciar caixa. */
    private static final Set<String> RESERVED = Set.of("api", "actuator", "swagger-ui", "v3", "docs", "health",
            "livez", "readyz", "error", "login", "logout", "admin", "static", "assets");

    private ShortCode() {
    }

    public static boolean isWellFormed(String candidate) {
        return candidate != null && WELL_FORMED.matcher(candidate).matches();
    }

    public static boolean isReserved(String candidate) {
        return RESERVED.contains(candidate.toLowerCase(Locale.ROOT));
    }
}
