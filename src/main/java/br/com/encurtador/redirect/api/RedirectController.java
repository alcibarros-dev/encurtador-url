package br.com.encurtador.redirect.api;

import br.com.encurtador.link.LinkLookup;
import br.com.encurtador.redirect.application.ClickReporter;
import br.com.encurtador.redirect.application.LinkResolver;
import br.com.encurtador.redirect.application.Resolution;
import br.com.encurtador.shared.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * {@code favicon.ico} e afins não chegam aqui; {@code /error} e o actuator têm precedência (ADR-0001, D4).
 */
@RestController
@Tag(name = "Redirecionamento", description = "Rota pública na raiz: é o link curto que circula.")
class RedirectController {

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

    @GetMapping("/{code:" + LinkLookup.CODE_PATTERN + "}")
    @Operation(summary = "Redireciona para o destino", description = "`GET` conta um clique; `HEAD` responde igual, "
            + "sem contar. Sem cache no navegador, para que remoção e expiração valham no próximo acesso.")
    @ApiResponse(responseCode = "302", description = "Redirecionamento", content = @Content, headers = {
            @Header(name = HttpHeaders.LOCATION, description = "URL de destino"),
            @Header(name = HttpHeaders.CACHE_CONTROL, description = "`private, no-store`")})
    @ApiResponse(responseCode = "404", description = "`LINK_NOT_FOUND`")
    @ApiResponse(responseCode = "410", description = "`LINK_EXPIRED` ou `LINK_DELETED`")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: limite por IP")
    ResponseEntity<Void> redirect(@PathVariable String code, @Parameter(hidden = true) HttpMethod method,
                                  @Parameter(description = "Só o host é guardado, para as estatísticas")
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
