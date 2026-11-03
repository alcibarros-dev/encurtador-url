package br.com.encurtador.security;

import java.util.List;

/**
 * Rotas que um módulo libera sem token (ADR-0002, D3). Cada módulo declara as suas num bean; o {@code security} só
 * conhece as rotas de infraestrutura (probes, Swagger, {@code /error}). O resto de {@code /api/v1/**} exige token, e
 * qualquer outra rota é negada.
 */
@FunctionalInterface
public interface PublicRoutes {

    List<Route> routes();

    /**
     * @param method  método HTTP
     * @param pattern padrão de caminho do Spring ({@code /*} é um segmento só)
     */
    record Route(String method, String pattern) {

        public static Route get(String pattern) {
            return new Route("GET", pattern);
        }

        public static Route head(String pattern) {
            return new Route("HEAD", pattern);
        }

        public static Route post(String pattern) {
            return new Route("POST", pattern);
        }
    }
}
