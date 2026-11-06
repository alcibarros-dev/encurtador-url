package br.com.encurtador.security.jwt;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.util.List;

/**
 * Chaves públicas do emissor, buscadas no JWKS sob demanda (ADR-0003, D6): cache com renovação antecipada, até duas
 * buscas fora de hora por janela (um {@code kid} desconhecido força uma), tolerância a queda e timeouts curtos. Nenhuma
 * requisição autenticada chama o servidor de autorização enquanto o cache vale.
 * <p>
 * Quando o limite de buscas barra a procura de um {@code kid} desconhecido, a busca é feita nas últimas chaves obtidas:
 * o token sai com 401, como qualquer token assinado por uma chave que o emissor não publica. Sem nenhuma chave obtida
 * ainda, a falha continua sendo de indisponibilidade (503).
 */
final class JwksKeySource implements JWKSource<SecurityContext>, Closeable {

    private static final Logger log = LoggerFactory.getLogger(JwksKeySource.class);
    /** O JWKS do emissor tem uma ou duas chaves; 50 KiB sobram. */
    private static final int SIZE_LIMIT_BYTES = 50 * 1024;

    private final RecordingSource fetched;
    private final JWKSource<SecurityContext> keys;

    JwksKeySource(JwtValidationProperties properties) {
        JwtValidationProperties.Jwks jwks = properties.jwks();
        var retriever = new DefaultResourceRetriever((int) jwks.connectTimeout().toMillis(),
                (int) jwks.readTimeout().toMillis(), SIZE_LIMIT_BYTES);
        try {
            this.fetched = new RecordingSource(
                    new URLBasedJWKSetSource<>(properties.jwkSetUri().toURL(), retriever));
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException("app.jwt.jwk-set-uri inválido: " + properties.jwkSetUri(), ex);
        }
        this.keys = JWKSourceBuilder.create(fetched)
                .retrying(false)
                .outageTolerant(jwks.outageTolerance().toMillis())
                .rateLimited(jwks.minRefreshInterval().toMillis())
                .cache(jwks.cacheTtl().toMillis(), jwks.refreshTimeout().toMillis())
                .refreshAheadCache(jwks.refreshAhead().toMillis(), false)
                .build();
    }

    @Override
    public List<JWK> get(JWKSelector selector, @Nullable SecurityContext context) throws KeySourceException {
        try {
            return keys.get(selector, context);
        } catch (RateLimitReachedException ex) {
            JWKSet last = fetched.last();
            if (last == null) {
                throw ex;
            }
            log.debug("Limite de buscas ao JWKS atingido; usando as últimas chaves obtidas");
            return selector.select(last);
        }
    }

    @Override
    public void close() {
        if (keys instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /** Guarda o último JWKS obtido com sucesso, para a busca de um {@code kid} barrada pelo limite. */
    private static final class RecordingSource implements JWKSetSource<SecurityContext> {

        private final JWKSetSource<SecurityContext> delegate;
        private volatile @Nullable JWKSet last;

        RecordingSource(JWKSetSource<SecurityContext> delegate) {
            this.delegate = delegate;
        }

        @Nullable JWKSet last() {
            return last;
        }

        @Override
        public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime,
                                @Nullable SecurityContext context) throws KeySourceException {
            try {
                JWKSet set = delegate.getJWKSet(refreshEvaluator, currentTime, context);
                last = set;
                return set;
            } catch (KeySourceException ex) {
                log.warn("Falha ao buscar o JWKS: {}", ex.getMessage());
                throw ex;
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
