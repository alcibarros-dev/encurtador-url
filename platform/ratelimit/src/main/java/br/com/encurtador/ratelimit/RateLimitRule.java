package br.com.encurtador.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Regra de rate limit que um módulo declara para as próprias rotas, num bean (ADR-0002, D5). O limite vem de
 * {@code app.rate-limit.<nome>}.
 *
 * @param name    nome da regra, também usado como tag das métricas
 * @param matches quais requisições a regra cobre
 * @param key     quem é limitado
 */
public record RateLimitRule(String name, Predicate<HttpServletRequest> matches, Key key) {

    /** Um segmento só, como {@code /aZ3kP9q}. */
    private static final Pattern SINGLE_SEGMENT = Pattern.compile("/[^/]+");

    public enum Key {
        /** Endereço do cliente; IPv6 agregado por /64. */
        IP,
        /** Usuário autenticado; requisição sem usuário não conta (a autorização já a recusou). */
        USER
    }

    /** Método e caminho exatos. */
    public static RateLimitRule exact(String name, String method, String path, Key key) {
        return new RateLimitRule(name, request -> method.equals(request.getMethod()) && path.equals(pathOf(request)),
                key);
    }

    /** Um único segmento de caminho com um dos métodos dados. */
    public static RateLimitRule singleSegment(String name, Set<String> methods, Key key) {
        return new RateLimitRule(name,
                request -> methods.contains(request.getMethod()) && SINGLE_SEGMENT.matcher(pathOf(request)).matches(),
                key);
    }

    /**
     * Comparação literal é segura: o firewall do Spring Security, que roda antes do filtro, já recusou {@code //},
     * {@code ;} e barras codificadas, e o MVC não atende variações de caixa ou barra final.
     */
    static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
