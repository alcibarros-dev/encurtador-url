package br.com.encurtador.bff.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * As rotas da SPA (ADR-0004, D6 e D12) entregam o {@code index.html}, que o Angular resolve no navegador. Sem isto, o
 * handler do {@code shared} transformaria essas rotas em 404 problem+json.
 */
@Controller
class SpaController {

    static final Resource INDEX = new ClassPathResource("META-INF/resources/index.html");

    /** O {@code index.html} nunca vem do cache sem revalidar: uma versão nova chega na hora (D9). */
    static ResponseEntity<Resource> index(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.TEXT_HTML)
                .body(INDEX);
    }

    @GetMapping({"/", "/links", "/links/{code}", "/estatisticas", "/conta"})
    ResponseEntity<Resource> spa() {
        return index(HttpStatus.OK);
    }
}
