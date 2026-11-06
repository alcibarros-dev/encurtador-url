package br.com.encurtador.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Access tokens no formato do auth-server (ADR-0003, D5), assinados com a chave do {@link TestJwks}: RS256, {@code kid}
 * no cabeçalho, sem {@code typ}, {@code iss} e {@code aud} da configuração, 15 minutos pelo relógio da aplicação.
 */
public final class TestTokens {

    private final Clock clock;
    private final String issuer;
    private final String audience;

    TestTokens(Clock clock, String issuer, String audience) {
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
    }

    public String accessToken(String subject) {
        return token(subject).sign();
    }

    /** Ponto de partida para um token fora do padrão: outro emissor, outra chave, expirado. */
    public Builder token(String subject) {
        return new Builder(subject);
    }

    public final class Builder {

        private final String subject;
        private String tokenIssuer = issuer;
        private String tokenAudience = audience;
        private @Nullable String type;
        private RSAKey key = TestJwks.KEY;
        private Instant issuedAt = clock.instant();
        private Duration ttl = Duration.ofMinutes(15);

        private Builder(String subject) {
            this.subject = subject;
        }

        public Builder issuer(String value) {
            this.tokenIssuer = value;
            return this;
        }

        public Builder audience(String value) {
            this.tokenAudience = value;
            return this;
        }

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        /** Assina com outra chave, com o {@code kid} dela. */
        public Builder signedWith(RSAKey value) {
            this.key = value;
            return this;
        }

        public Builder issuedAt(Instant value) {
            this.issuedAt = value;
            return this;
        }

        public Builder ttl(Duration value) {
            this.ttl = value;
            return this;
        }

        public String sign() {
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
            if (type != null) {
                header.type(new JOSEObjectType(type));
            }
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(tokenIssuer)
                    .audience(tokenAudience)
                    .claim("scope", "openid email")
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(ttl)))
                    .build();
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException ex) {
                throw new IllegalStateException(ex);
            }
            return jwt.serialize();
        }
    }
}
