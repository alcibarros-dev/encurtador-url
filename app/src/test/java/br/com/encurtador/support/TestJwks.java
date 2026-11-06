package br.com.encurtador.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JWKS do servidor de autorização nos testes de integração (ADR-0003, D12): um servidor HTTP local publica a parte
 * pública de uma chave RSA de teste, e o app a busca pelo mesmo caminho de produção. Quem o derruba ou troca as chaves
 * precisa chamar {@link #restore()} ao terminar.
 */
public final class TestJwks implements AutoCloseable {

    /** Chave dos tokens de teste; o {@code kid} imita o do auth-server. */
    public static final RSAKey KEY = rsaKey("test-1");

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean available = true;
    private volatile JWKSet published;

    TestJwks() {
        publish(KEY);
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/oauth2/jwks", this::handle);
        server.start();
    }

    public static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/jwks");
    }

    /** Buscas recebidas desde a criação. */
    public int requests() {
        return requests.get();
    }

    /** Publica só a parte pública das chaves, como o auth-server na rotação (a nova e a anterior). */
    public void publish(RSAKey... keys) {
        published = new JWKSet(Arrays.stream(keys).map(JWK::toPublicJWK).toList());
    }

    /** Servidor de autorização fora: toda busca recebe 503. */
    public void down() {
        available = false;
    }

    public void restore() {
        available = true;
        publish(KEY);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            if (!available) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] body = published.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
