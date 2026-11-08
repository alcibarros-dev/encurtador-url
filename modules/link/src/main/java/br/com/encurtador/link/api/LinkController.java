package br.com.encurtador.link.api;

import br.com.authcore.security.CurrentUser;
import br.com.authcore.security.OpenApiSecurity;
import br.com.encurtador.link.application.LinkService;
import br.com.encurtador.link.application.LinkView;
import br.com.encurtador.shared.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Links do usuário autenticado (Bearer JWT; ADR-0001, D12). */
@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Só os links do usuário autenticado; link de outro usuário responde 404.")
@SecurityRequirement(name = OpenApiSecurity.BEARER)
class LinkController {

    static final int MAX_PAGE_SIZE = 100;
    /** Mantém {@code page × size} dentro de um {@code int}, o tipo do offset no JPA: acima disso seria um 500. */
    static final int MAX_PAGE = 10_000;

    private final LinkService links;
    private final String shortUrlPrefix;

    LinkController(LinkService links, AppProperties app) {
        this.links = links;
        String baseUrl = app.baseUrl().toString();
        this.shortUrlPrefix = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @PostMapping
    @Operation(summary = "Cria um link", description = "Código de 7 caracteres gerado, ou um alias. O destino não "
            + "pode ser trocado depois; sem `expiresAt`, o link vale 30 dias.")
    @ApiResponse(responseCode = "201", description = "Link criado; `Location` aponta para ele",
            useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`: destino, alias ou validade")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED` ou `INVALID_TOKEN`")
    @ApiResponse(responseCode = "409", description = "`ALIAS_TAKEN` ou `LINK_QUOTA_EXCEEDED`")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: limite por usuário")
    @ApiResponse(responseCode = "503", description = "`CODE_GENERATION_EXHAUSTED`: tente de novo")
    ResponseEntity<LinkResponse> create(@CurrentUser long userId, @RequestBody CreateLinkRequest request) {
        LinkView link = links.create(userId, request.url(), request.alias(), request.expiresAtInstant());
        return ResponseEntity.created(URI.create("/api/v1/links/" + link.code())).body(response(link));
    }

    @GetMapping
    @Operation(summary = "Lista os links", description = "Mais recentes primeiro; inclui os expirados, não os "
            + "removidos.")
    @ApiResponse(responseCode = "200", description = "Página de links", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`: `page` ou `size` fora do intervalo")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED` ou `INVALID_TOKEN`")
    PageResponse<LinkResponse> list(@CurrentUser long userId,
                                    @Parameter(description = "A partir de 0")
                                    @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
                                    @Parameter(description = "De 1 a 100")
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return PageResponse.from(links.list(userId, page, size).map(this::response));
    }

    @GetMapping("/{code}")
    @Operation(summary = "Consulta um link")
    @ApiResponse(responseCode = "200", description = "O link", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED` ou `INVALID_TOKEN`")
    @ApiResponse(responseCode = "404", description = "`LINK_NOT_FOUND`: inexistente, removido ou de outro usuário")
    LinkResponse get(@CurrentUser long userId, @PathVariable String code) {
        return response(links.get(userId, code));
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um link", description = "O código nunca é reaproveitado; o redirecionamento passa "
            + "a responder 410.")
    @ApiResponse(responseCode = "204", description = "Link removido")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED` ou `INVALID_TOKEN`")
    @ApiResponse(responseCode = "404", description = "`LINK_NOT_FOUND`")
    void delete(@CurrentUser long userId, @PathVariable String code) {
        links.delete(userId, code);
    }

    private LinkResponse response(LinkView link) {
        return LinkResponse.from(link, shortUrlPrefix);
    }
}
