package br.com.encurtador.security.jwt;

import br.com.encurtador.identity.application.AccessTokenIssuer;
import br.com.encurtador.identity.application.JwtIssuerProperties;
import br.com.encurtador.shared.crypto.HmacSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ida e volta encode → decode com o mesmo segredo e o mesmo {@link Clock} (ADR-0001, D9 e spike da F0): a emissão
 * é do provedor de identidade, e a validação, do módulo de segurança (ADR-0002, D10).
 */
class JwtRoundTripTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "segredo-de-teste-com-mais-de-32-bytes!".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString(
            "outro-segredo-de-teste-com-32-bytes-ou-mais".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration SKEW = Duration.ofSeconds(5);

    @Test
    void issuedTokenRoundTripsWithExpectedClaims() {
        AccessTokenIssuer.AccessToken token = issuer(SECRET, properties("encurtador", "encurtador-api")).issue(42L);

        Jwt jwt = decoderAt(NOW).decode(token.value());

        assertThat(token.expiresIn()).isEqualTo(TTL);
        assertThat(jwt.getSubject()).isEqualTo("42");
        // getIssuer() converte para URL; o emissor é uma string simples (StringOrURI), válida no JWT.
        assertThat(jwt.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("encurtador");
        assertThat(jwt.getAudience()).containsExactly("encurtador-api");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(jwt.getHeaders()).containsEntry("alg", MacAlgorithm.HS256.getName()).containsEntry("typ", "JWT");
    }

    @Test
    void acceptsTokenWithinClockSkew() {
        String token = issuer(SECRET, properties("encurtador", "encurtador-api")).issue(1L).value();

        assertThat(decoderAt(NOW.plus(TTL).plus(SKEW).minusSeconds(1)).decode(token).getSubject()).isEqualTo("1");
    }

    @Test
    void rejectsExpiredToken() {
        String token = issuer(SECRET, properties("encurtador", "encurtador-api")).issue(1L).value();

        assertThatThrownBy(() -> decoderAt(NOW.plus(TTL).plus(SKEW).plusSeconds(1)).decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenForAnotherAudience() {
        String token = issuer(SECRET, properties("encurtador", "outra-api")).issue(1L).value();

        assertThatThrownBy(() -> decoderAt(NOW).decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        String token = issuer(SECRET, properties("outro-emissor", "encurtador-api")).issue(1L).value();

        assertThatThrownBy(() -> decoderAt(NOW).decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        String token = issuer(OTHER_SECRET, properties("encurtador", "encurtador-api")).issue(1L).value();

        assertThatThrownBy(() -> decoderAt(NOW).decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                "{\"sub\":\"1\",\"iss\":\"encurtador\",\"aud\":\"encurtador-api\",\"exp\":4102444800}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoderAt(NOW).decode(header + "." + payload + "."))
                .isInstanceOf(BadJwtException.class);
    }

    private static AccessTokenIssuer issuer(String secret, JwtIssuerProperties properties) {
        var encoder = NimbusJwtEncoder.withSecretKey(HmacSecret.fromBase64(secret)).algorithm(MacAlgorithm.HS256).build();
        return new AccessTokenIssuer(encoder, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static JwtDecoder decoderAt(Instant instant) {
        var properties = new JwtValidationProperties("encurtador", "encurtador-api", SKEW);
        return JwtDecoderConfig.decoder(properties, HmacSecret.fromBase64(SECRET), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static JwtIssuerProperties properties(String issuer, String audience) {
        return new JwtIssuerProperties(issuer, audience, TTL);
    }
}
