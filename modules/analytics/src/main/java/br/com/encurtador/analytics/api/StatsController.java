package br.com.encurtador.analytics.api;

import br.com.encurtador.analytics.application.StatsService;
import br.com.encurtador.security.CurrentUser;
import br.com.encurtador.security.OpenApiSecurity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Estatísticas de um link do usuário autenticado (Bearer JWT; ADR-0001, D12). */
@RestController
@Tag(name = "Estatísticas", description = "Cliques chegam em segundos: a contagem é eventual.")
@SecurityRequirement(name = OpenApiSecurity.BEARER)
class StatsController {

    private final StatsService stats;

    StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/api/v1/links/{code}/stats")
    @Operation(summary = "Estatísticas de um link", description = "Total histórico, total do período, série diária "
            + "e os 10 referers mais frequentes (`host` nulo é acesso direto). Período de até 90 dias.")
    @ApiResponse(responseCode = "200", description = "Estatísticas", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`: período ou fuso inválido")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED` ou `INVALID_TOKEN`")
    @ApiResponse(responseCode = "404", description = "`LINK_NOT_FOUND`: inexistente, removido ou de outro usuário")
    LinkStatsResponse stats(@CurrentUser long userId, @PathVariable String code,
                            @Parameter(description = "Início do período, inclusive; padrão: 29 dias antes de `to`")
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @Parameter(description = "Fim do período, inclusive; padrão: hoje no fuso `tz`")
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @Parameter(description = "Fuso IANA das datas; padrão: America/Sao_Paulo",
                                    example = "America/Sao_Paulo")
                            @RequestParam(required = false) String tz) {
        return LinkStatsResponse.from(stats.stats(userId, code, from, to, tz));
    }
}
