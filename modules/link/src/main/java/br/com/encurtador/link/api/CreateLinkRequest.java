package br.com.encurtador.link.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Sem Bean Validation de propósito: as regras dos três campos são do domínio ({@code TargetUrl}, {@code Alias} e
 * validade) e o serviço devolve todas as violações de uma vez, no mesmo formato do Bean Validation (ADR-0001, D10).
 * As anotações {@code @Schema} são só documentação.
 *
 * @param alias     opcional; sem ele, o código é gerado
 * @param expiresAt opcional; ISO-8601 com offset
 */
record CreateLinkRequest(
        @Schema(description = "http ou https; domínio internacional em Punycode",
                example = "https://exemplo.com/artigo?id=42", requiredMode = Schema.RequiredMode.REQUIRED)
        String url,
        @Schema(description = "De 4 a 32 caracteres: letras, números, `_` e `-`", example = "promo-novembro")
        String alias,
        @Schema(description = "De 1 minuto a 365 dias no futuro; padrão: 30 dias",
                example = "2026-12-31T23:59:00-03:00")
        OffsetDateTime expiresAt) {

    Instant expiresAtInstant() {
        return expiresAt == null ? null : expiresAt.toInstant();
    }
}
