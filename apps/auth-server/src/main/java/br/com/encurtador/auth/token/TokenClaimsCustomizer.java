package br.com.encurtador.auth.token;

import br.com.encurtador.auth.config.AuthServerProperties;
import br.com.encurtador.auth.credential.Credential;
import br.com.encurtador.auth.credential.CredentialRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Claims dos tokens (ADR-0003, D5; ADR-0004, D4): sem isto, o SAS usaria como {@code sub} o nome do principal (o
 * e-mail) e como {@code aud} o {@code client_id}, e não incluiria o e-mail.
 * <ul>
 *     <li>{@code sub}: o identificador estável da credencial, em todos os tokens;</li>
 *     <li>{@code email}: no access token e no {@code id_token};</li>
 *     <li>{@code aud}: o app ({@code encurtador-api}), só no access token;</li>
 *     <li>{@code kid} no cabeçalho: só a chave atual assina, mesmo com a anterior publicada no JWKS.</li>
 * </ul>
 */
@Component
class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final CredentialRepository credentials;
    private final AuthServerProperties properties;
    private final CurrentSigningKey signingKey;

    TokenClaimsCustomizer(CredentialRepository credentials, AuthServerProperties properties,
                          CurrentSigningKey signingKey) {
        this.credentials = credentials;
        this.properties = properties;
        this.signingKey = signingKey;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        context.getJwsHeader().keyId(signingKey.keyId());
        Authentication principal = context.getPrincipal();
        Credential credential = credentials.findByEmail(principal.getName())
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                        "A conta desta autorização não existe mais.", null)));
        context.getClaims().subject(credential.subject()).claim("email", credential.email());
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            // ArrayList, e não List.of: os claims são guardados por Jackson, que só desserializa tipos conhecidos.
            context.getClaims().audience(new ArrayList<>(List.of(properties.audience())));
        }
    }
}
