package br.com.encurtador.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

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
 * JWKS de teste num servidor HTTP local, no lugar do servidor de autorização (ADR-0003, D12): conta as buscas e pode
 * ficar fora do ar. Também assina tokens com as chaves que gera.
 */
final class JwksStub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean available = true;
    private volatile JWKSet published;

    JwksStub(RSAKey... keys) {
        publish(keys);
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.createContext("/oauth2/jwks", this::handle);
        server.start();
    }

    static RSAKey rsaKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Assina com a chave e o {@code kid} dela. */
    static String sign(RSAKey key, @Nullable String type, JWTClaimsSet claims) {
        return sign(key, key.getKeyID(), type, claims);
    }

    static String sign(RSAKey key, String kid, @Nullable String type, JWTClaimsSet claims) {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid);
        if (type != null) {
            header.type(new JOSEObjectType(type));
        }
        SignedJWT jwt = new SignedJWT(header.build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        return jwt.serialize();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/jwks");
    }

    int requests() {
        return requests.get();
    }

    /** Publica só a parte pública das chaves. */
    void publish(RSAKey... keys) {
        published = new JWKSet(Arrays.stream(keys).map(JWK::toPublicJWK).toList());
    }

    void available(boolean available) {
        this.available = available;
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
