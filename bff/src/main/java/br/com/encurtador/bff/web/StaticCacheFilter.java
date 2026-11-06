package br.com.encurtador.bff.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Cache dos arquivos da SPA (ADR-0004, D9): os que têm hash no nome não mudam e ficam um ano no cache
 * ({@code immutable}); o {@code index.html}, que aponta para eles, sempre revalida.
 */
@Component
class StaticCacheFilter extends OncePerRequestFilter {

    /** Nomes que o build do Angular gera: {@code main-62I54CQD.js}, {@code chunk-BOY3a0Qa.js}, {@code styles-….css}. */
    private static final Pattern HASHED = Pattern.compile("^/[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8}\\.(js|css)$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (HASHED.matcher(path).matches()) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
        } else if ("/index.html".equals(path)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        }
        chain.doFilter(request, response);
    }
}
