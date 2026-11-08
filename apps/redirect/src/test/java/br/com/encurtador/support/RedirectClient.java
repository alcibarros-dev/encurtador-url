package br.com.encurtador.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/** Cliente HTTP dos testes: fala com o serviço real pela rede, sem seguir redirecionamentos. */
public final class RedirectClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    /** IPv4 explícito: o rate limit é por IP, e {@code localhost} pode resolver para {@code ::1}. */
    public RedirectClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public Response get(String path, String headerName, String headerValue) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).header(headerName, headerValue).GET());
    }

    public Response head(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).HEAD());
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isEmpty() ? JSON.missingNode() : JSON.readTree(response.body());
            return new Response(response.statusCode(), response.headers(), body);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public record Response(int status, HttpHeaders headers, JsonNode body) {

        public String text(String field) {
            return body.path(field).asString();
        }

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }
}
