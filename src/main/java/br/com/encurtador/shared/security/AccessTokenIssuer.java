package br.com.encurtador.shared.security;

import br.com.encurtador.shared.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Emite access tokens: {@code sub} = id do usuário, {@code iss}, {@code aud}, {@code iat} e {@code exp}
 * calculados com o {@link Clock} da aplicação (ADR-0001, D9).
 */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AppProperties.Jwt properties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, AppProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties.jwt();
        this.clock = clock;
    }

    public AccessToken issue(long userId) {
        Instant issuedAt = clock.instant();
        Duration ttl = properties.accessTokenTtl();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(userId))
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, ttl);
    }

    public record AccessToken(String value, Duration expiresIn) {
    }
}
