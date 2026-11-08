package br.com.encurtador.support;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Proxy do Toxiproxy entre a aplicação e o Redis, para os testes de degradação (ADR-0001, D14, cenários 4 e 5).
 * Quem o derruba ou pendura precisa chamar {@link #restore()} ao terminar.
 */
public final class RedisProxy {

    /** Uma das portas que o {@link ToxiproxyContainer} expõe para proxies. */
    private static final int LISTEN_PORT = 8666;
    /** Bem acima do timeout de 100 ms dos comandos. */
    private static final long HANG_LATENCY_MS = 2_000;

    private final Proxy proxy;
    private final String host;
    private final int port;

    private RedisProxy(Proxy proxy, String host, int port) {
        this.proxy = proxy;
        this.host = host;
        this.port = port;
    }

    static RedisProxy create(ToxiproxyContainer toxiproxy, String upstream) {
        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        try {
            Proxy proxy = client.createProxy("redis", "0.0.0.0:" + LISTEN_PORT, upstream);
            return new RedisProxy(proxy, toxiproxy.getHost(), toxiproxy.getMappedPort(LISTEN_PORT));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    /** Redis parado: conexões fechadas e novas recusadas. */
    public void cut() {
        run(proxy::disable);
    }

    /**
     * Redis pendurado: a conexão continua aberta, mas as respostas atrasam muito além do timeout. Usa
     * {@code latency}, e não {@code timeout}: o {@code timeout} descarta bytes, o que um TCP real nunca faz, e
     * dessincronizaria as respostas na conexão do Lettuce.
     */
    public void hang() {
        run(() -> proxy.toxics().latency("pendurado", ToxicDirection.DOWNSTREAM, HANG_LATENCY_MS));
    }

    public void restore() {
        run(() -> {
            proxy.enable();
            for (Toxic toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        });
    }

    private static void run(IoAction action) {
        try {
            action.run();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
