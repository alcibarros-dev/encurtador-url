package br.com.encurtador.bff.web;

import br.com.encurtador.shared.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Endereço que não existe: o navegador recebe o {@code index.html} com 404, e a SPA mostra a página de não
 * encontrado (ADR-0004, D6); as chamadas da SPA, o problem+json de sempre.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotFoundAdvice {

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound(HttpServletRequest request) {
        String path = request.getRequestURI();
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        boolean browser = accept != null && MediaType.parseMediaTypes(accept).stream()
                .anyMatch(type -> type.isCompatibleWith(MediaType.TEXT_HTML) && !type.isWildcardType());
        if (browser && !path.startsWith("/api/") && !path.startsWith("/bff/")) {
            return SpaController.index(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ProblemDetails.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso não encontrado."));
    }
}
