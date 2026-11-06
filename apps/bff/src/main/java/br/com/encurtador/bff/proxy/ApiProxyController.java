package br.com.encurtador.bff.proxy;

import br.com.encurtador.bff.config.BffProperties;
import br.com.encurtador.shared.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;

/**
 * Repasse de {@code /api/**} ao app com o access token da sessão (ADR-0004, D5). Só as rotas que o painel usa passam;
 * do navegador vão só o método, o caminho, a query, o corpo e os cabeçalhos {@code Content-Type} e {@code Accept}:
 * cookies, {@code Authorization} e {@code X-Forwarded-*} nunca chegam ao app. A resposta volta sem tradução, com os
 * cabeçalhos que a SPA usa.
 */
@RestController
class ApiProxyController {

    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";

    private static final Logger log = LoggerFactory.getLogger(ApiProxyController.class);
    private static final List<String> REQUEST_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT);
    private static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION,
            HttpHeaders.RETRY_AFTER, HttpHeaders.CACHE_CONTROL);

    private final RestClient app;
    private final String apiUrl;

    ApiProxyController(RestClient appRestClient, BffProperties properties) {
        this.app = appRestClient;
        String url = properties.apiUrl().toString();
        this.apiUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** As rotas do painel (D5); qualquer outra de {@code /api/**} responde 404 aqui, sem chegar ao app. */
    static boolean isAllowed(String path) {
        return path.equals("/api/v1/stats") || path.equals("/api/v1/links") || path.startsWith("/api/v1/links/");
    }

    @RequestMapping("/api/**")
    ResponseEntity<?> proxy(HttpServletRequest request, @RequestBody(required = false) byte @Nullable [] body) {
        // URI ainda codificada: o firewall do Spring Security já recusou "..", "//" e barras codificadas.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!isAllowed(path)) {
            return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso não encontrado.");
        }
        String query = request.getQueryString();
        URI target = URI.create(apiUrl + path + (query == null ? "" : "?" + query));
        RestClient.RequestBodySpec call = app.method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(headers -> REQUEST_HEADERS.forEach(name -> {
                    String value = request.getHeader(name);
                    if (value != null) {
                        headers.set(name, value);
                    }
                }));
        if (body != null && body.length > 0) {
            call.body(body);
        }
        return call.exchange((upstreamRequest, upstream) -> {
            HttpHeaders headers = new HttpHeaders();
            for (String name : RESPONSE_HEADERS) {
                for (String value : upstream.getHeaders().getOrEmpty(name)) {
                    headers.add(name, HttpHeaders.LOCATION.equals(name) ? relativeLocation(value) : value);
                }
            }
            byte[] content;
            try {
                content = upstream.getBody().readAllBytes();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return ResponseEntity.status(upstream.getStatusCode()).headers(headers).body(content);
        }, true);
    }

    /** O {@code Location} do app aponta para o endereço interno dele; a SPA o vê pelo BFF. */
    private String relativeLocation(String location) {
        return location.startsWith(apiUrl + "/") ? location.substring(apiUrl.length()) : location;
    }

    /** App fora do ar ou lento (D5). */
    @ExceptionHandler({ResourceAccessException.class, UncheckedIOException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception ex) {
        log.warn("App indisponível no repasse: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, UPSTREAM_UNAVAILABLE,
                "O serviço está indisponível agora. Tente de novo em instantes.");
    }

    /**
     * O refresh token não vale mais (expirou, foi revogado ou a senha foi trocada, ADR-0003 D9): a sessão do BFF
     * acaba, e a SPA leva o usuário ao login.
     */
    @ExceptionHandler(OAuth2AuthorizationException.class)
    ResponseEntity<ProblemDetail> sessionExpired(OAuth2AuthorizationException ex, HttpServletRequest request) {
        log.info("Renovação do access token recusada ({}); sessão encerrada", ex.getError().getErrorCode());
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return problem(HttpStatus.UNAUTHORIZED, SESSION_EXPIRED, "Sua sessão expirou. Entre de novo para continuar.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(ProblemDetails.of(status, code, detail));
    }
}
