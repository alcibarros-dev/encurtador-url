package br.com.encurtador.link.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Envelope estável de paginação da API (ADR-0001, D12). Não expõe o JSON do {@link Page} do Spring Data, que muda
 * entre versões.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
