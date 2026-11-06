package br.com.encurtador.bff.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Servidor HTTP falso no lugar do app ou do auth-server (ADR-0004, D11): guarda cada requisição recebida e responde
 * o que o teste programou para o caminho. Quem programa respostas precisa chamar {@link #reset()} ao terminar.
 */
public final class FakeServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    public FakeServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Resposta para o caminho, sem query. */
    public void reply(String path, int status, String contentType, String body, Map<String, String> headers) {
        replies.put(path, new Reply(status, contentType, body, headers, Duration.ZERO));
    }

    public void reply(String path, int status, String json) {
        reply(path, status, "application/json", json, Map.of());
    }

    /** Responde depois de esperar, para simular um serviço lento. */
    public void replySlowly(String path, Duration delay) {
        replies.put(path, new Reply(200, "application/json", "{}", Map.of(), delay));
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public Received last() {
        return received.getLast();
    }

    public void reset() {
        received.clear();
        replies.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach(headers::put);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), headers, new String(body, StandardCharsets.UTF_8)));
            Reply reply = replies.getOrDefault(exchange.getRequestURI().getPath(),
                    new Reply(404, "application/json", "{}", Map.of(), Duration.ZERO));
            if (!reply.delay().isZero()) {
                try {
                    Thread.sleep(reply.delay());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] content = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            reply.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(reply.status(), content.length == 0 ? -1 : content.length);
            if (content.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(content);
                }
            }
        } catch (IOException ex) {
            // O cliente desistiu (timeout do teste de lentidão).
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Received(String method, String path, String query, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? null : values.getFirst();
        }
    }

    private record Reply(int status, String contentType, String body, Map<String, String> headers, Duration delay) {
    }
}
