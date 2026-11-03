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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cliente HTTP dos testes de integração: fala com a aplicação real pela rede, passando pela cadeia de segurança
 * completa.
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    /** IPv4 explícito: o rate limit é por IP, e {@code localhost} pode resolver para {@code ::1}. */
    public ApiClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    public Response get(String path, String bearerToken) {
        return send("GET", path, null, bearerToken);
    }

    public Response head(String path) {
        return send("HEAD", path, null, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, JSON.writeValueAsString(body), null);
    }

    public Response post(String path, Object body, String bearerToken) {
        return send("POST", path, JSON.writeValueAsString(body), bearerToken);
    }

    public Response postRaw(String path, String rawJson) {
        return send("POST", path, rawJson, null);
    }

    public Response put(String path, Object body, String bearerToken) {
        return send("PUT", path, JSON.writeValueAsString(body), bearerToken);
    }

    public Response delete(String path, String bearerToken) {
        return send("DELETE", path, null, bearerToken);
    }

    private Response send(String method, String path, String json, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (json != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
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

        /** Valores de uma propriedade de cada item de {@code errors[]}, na ordem da resposta. */
        public List<String> errors(String property) {
            List<String> values = new ArrayList<>();
            for (JsonNode error : body.path("errors")) {
                values.add(error.path(property).asString());
            }
            return values;
        }
    }
}
