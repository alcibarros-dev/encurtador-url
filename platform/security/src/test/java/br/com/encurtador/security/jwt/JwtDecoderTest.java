package br.com.encurtador.security.jwt;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validação pelo JWKS (ADR-0003, D6 e D12) com o decodificador real e um JWKS local: claims exigidos, {@code typ},
 * cache, {@code kid} desconhecido com limite de buscas, tolerância a queda e indisponibilidade.
 * <p>
 * {@link BadJwtException} vira 401; qualquer outra {@link JwtException} vira {@code AuthenticationServiceException},
 * o 503 da cadeia de segurança.
 */
class JwtDecoderTest {

    private static final String ISSUER = "http://auth.test";
    private static final String AUDIENCE = "encurtador-api";
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Duration SKEW = Duration.ofSeconds(5);
    private static final RSAKey KEY = JwksStub.rsaKey("chave-1");

    private final JwksStub jwks = new JwksStub(KEY);
    private JwksKeySource keys;

    @AfterEach
    void close() {
        if (keys != null) {
            keys.close();
        }
        jwks.close();
    }

    @Test
    void validTokenIsAcceptedAndTheKeysAreFetchedOnce() {
        JwtDecoder decoder = decoder(defaults());

        assertThat(decoder.decode(token(claims())).getSubject()).isEqualTo("assunto-1");
        assertThat(decoder.decode(token(claims())).getSubject()).isEqualTo("assunto-1");

        assertThat(jwks.requests()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"JWT", "at+jwt"})
    void acceptsTheTypesOfAnAccessToken(String type) {
        assertThat(decoder(defaults()).decode(JwksStub.sign(KEY, type, claims().build())).getSubject())
                .isEqualTo("assunto-1");
    }

    @Test
    void rejectsAnotherType() {
        String token = JwksStub.sign(KEY, "secevent+jwt", claims().build());

        assertThatThrownBy(() -> decoder(defaults()).decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsAnotherIssuer() {
        String token = token(claims().issuer("http://outro.test"));

        assertThatThrownBy(() -> decoder(defaults()).decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsAnotherAudience() {
        String token = token(claims().audience("encurtador-web"));

        assertThatThrownBy(() -> decoder(defaults()).decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void acceptsAnExpiredTokenOnlyWithinTheClockSkew() {
        String token = token(claims().expirationTime(Date.from(NOW.minusSeconds(4))));
        String expired = token(claims().expirationTime(Date.from(NOW.minus(SKEW).minusSeconds(1))));

        assertThat(decoder(defaults()).decode(token).getSubject()).isEqualTo("assunto-1");
        assertThatThrownBy(() -> decoder(defaults()).decode(expired)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherKeyUnderAKnownKid() {
        String forged = JwksStub.sign(JwksStub.rsaKey("chave-1"), null, claims().build());

        assertThatThrownBy(() -> decoder(defaults()).decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsUnsignedAndHmacTokens() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String payload = base64.encodeToString(claims().build().toString().getBytes(StandardCharsets.UTF_8));
        String none = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + payload + ".";
        String hmac = base64.encodeToString("{\"alg\":\"HS256\",\"kid\":\"chave-1\"}".getBytes(StandardCharsets.UTF_8))
                + "." + payload + "." + base64.encodeToString(new byte[32]);
        JwtDecoder decoder = decoder(defaults());

        assertThatThrownBy(() -> decoder.decode(none)).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode(hmac)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void unknownKidFetchesTheKeysAgainAndAcceptsARotatedKey() {
        JwtDecoder decoder = decoder(defaults());
        decoder.decode(token(claims()));
        RSAKey rotated = JwksStub.rsaKey("chave-2");
        jwks.publish(rotated, KEY);

        assertThat(decoder.decode(JwksStub.sign(rotated, null, claims().build())).getSubject()).isEqualTo("assunto-1");
        assertThat(jwks.requests()).isEqualTo(2);
    }

    @Test
    void unknownKidsAreLimitedAndAnsweredAsInvalidTokens() {
        JwtDecoder decoder = decoder(defaults());
        decoder.decode(token(claims()));

        for (int i = 0; i < 10; i++) {
            String token = JwksStub.sign(JwksStub.rsaKey("desconhecida-" + i), null, claims().build());
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
        }

        // A primeira busca e no máximo duas fora de hora por janela.
        assertThat(jwks.requests()).isLessThanOrEqualTo(3);
        assertThat(decoder.decode(token(claims())).getSubject()).isEqualTo("assunto-1");
    }

    @Test
    void knownKeysKeepWorkingWithTheJwksDownLongerThanTheCache() throws InterruptedException {
        JwtValidationProperties properties = properties(new JwtValidationProperties.Jwks(Duration.ofMillis(800),
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofHours(1), Duration.ofMillis(200),
                Duration.ofMillis(300)));
        JwtDecoder decoder = decoder(properties);
        decoder.decode(token(claims()));
        jwks.available(false);

        Thread.sleep(1_200);

        assertThat(decoder.decode(token(claims())).getSubject()).isEqualTo("assunto-1");
        assertThat(jwks.requests()).isGreaterThan(1);
    }

    @Test
    void withoutAnyKeyAndTheJwksDownTheFailureIsNotAnInvalidToken() {
        jwks.available(false);
        JwtDecoder decoder = decoder(defaults());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> decoder.decode(token(claims())))
                    .isInstanceOf(JwtException.class)
                    .isNotInstanceOf(BadJwtException.class);
        }
        assertThat(jwks.requests()).isLessThanOrEqualTo(2);
    }

    @Test
    void refreshAheadMustFitWithinTheCacheTtl() {
        var tight = new JwtValidationProperties.Jwks(Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofMillis(500), Duration.ofMillis(500));

        assertThat(tight.isRefreshAheadWithinCacheTtl()).isFalse();
        assertThat(defaults().jwks().isRefreshAheadWithinCacheTtl()).isTrue();
    }

    private JwtDecoder decoder(JwtValidationProperties properties) {
        keys = new JwksKeySource(properties);
        return JwtDecoderConfig.decoder(properties, keys, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private JwtValidationProperties defaults() {
        return properties(new JwtValidationProperties.Jwks(Duration.ofMinutes(15), Duration.ofMinutes(1),
                Duration.ofSeconds(30), Duration.ofHours(24), Duration.ofMillis(500), Duration.ofSeconds(2)));
    }

    private JwtValidationProperties properties(JwtValidationProperties.Jwks cache) {
        return new JwtValidationProperties(ISSUER, AUDIENCE, jwks.uri(), SKEW, cache);
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject("assunto-1")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(14))));
    }

    private static String token(JWTClaimsSet.Builder claims) {
        return JwksStub.sign(KEY, null, claims.build());
    }
}
