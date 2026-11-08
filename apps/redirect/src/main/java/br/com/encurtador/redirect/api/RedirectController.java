package br.com.encurtador.redirect.api;

import br.com.encurtador.redirect.application.ClickReporter;
import br.com.encurtador.redirect.application.LinkResolver;
import br.com.encurtador.redirect.application.Resolution;
import br.com.encurtador.shared.error.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /{code}} (e {@code HEAD}, atendido pelo mesmo mapeamento). O padrão exclui {@code .}, então
 * {@code favicon.ico} e afins não chegam aqui; {@code /error} e as probes têm precedência (ADR-0001, D4).
 * <p>
 * {@code GET} conta um clique; {@code HEAD} responde igual, sem contar. Respostas: 302 com {@code Location}, 404
 * {@code LINK_NOT_FOUND}, 410 {@code LINK_EXPIRED} ou {@code LINK_DELETED} e 429 {@code RATE_LIMITED}.
 */
@RestController
class RedirectController {

    /**
     * Forma de todo código curto, gerado ou alias. É a mesma do {@code ShortCode} da API, que cria os códigos; o
     * {@code CodePatternTest} confere as duas.
     */
    static final String CODE_PATTERN = "[A-Za-z0-9_-]{4,32}";

    static final String LINK_NOT_FOUND = "LINK_NOT_FOUND";
    static final String LINK_EXPIRED = "LINK_EXPIRED";
    static final String LINK_DELETED = "LINK_DELETED";

    /**
     * 302, e não 301: redirecionamento permanente fica em cache no navegador e em proxies sem prazo, o que esconderia
     * cliques, remoção e expiração.
     */
    private static final String NO_STORE = "private, no-store";

    private final LinkResolver resolver;
    private final ClickReporter clicks;

    RedirectController(LinkResolver resolver, ClickReporter clicks) {
        this.resolver = resolver;
        this.clicks = clicks;
    }

    @GetMapping("/{code:" + CODE_PATTERN + "}")
    ResponseEntity<Void> redirect(@PathVariable String code, HttpMethod method,
                                  @RequestHeader(value = HttpHeaders.REFERER, required = false) String referer) {
        return switch (resolver.resolve(code)) {
            case Resolution.Found found -> {
                // HEAD não é clique: verificadores de link e prévias o usam sem abrir o destino (D4).
                if (HttpMethod.GET.equals(method)) {
                    clicks.report(found.linkId(), referer);
                }
                // O destino já está normalizado em ASCII (D10): não há como injetar cabeçalho pelo Location.
                yield ResponseEntity.status(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, found.targetUrl())
                        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        .build();
            }
            case Resolution.Gone(Resolution.Reason reason) when reason == Resolution.Reason.EXPIRED ->
                    throw new ApiException(HttpStatus.GONE, LINK_EXPIRED, "Este link expirou.");
            case Resolution.Gone _ -> throw new ApiException(HttpStatus.GONE, LINK_DELETED, "Este link foi removido.");
            case Resolution.NotFound _ ->
                    throw new ApiException(HttpStatus.NOT_FOUND, LINK_NOT_FOUND, "Link não encontrado.");
        };
    }
}
