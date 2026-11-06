package br.com.encurtador.bff.session;

import br.com.encurtador.bff.config.BffProperties;
import br.com.encurtador.bff.config.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Revoga o refresh token no logout (RFC 7009; ADR-0004, D4), pelo endereço interno do auth-server. Uma falha não
 * impede o logout: a sessão do BFF é encerrada do mesmo jeito, e o token expira sozinho.
 */
@Component
public class TokenRevoker {

    private static final Logger log = LoggerFactory.getLogger(TokenRevoker.class);

    private final RestClient http;
    private final String revocationUri;
    private final String clientId;
    private final String clientSecret;

    TokenRevoker(BffProperties properties) {
        this.http = RestClient.builder().requestFactory(HttpClients.requestFactory(properties)).build();
        String internal = properties.authInternalUrl().toString();
        this.revocationUri = (internal.endsWith("/") ? internal : internal + "/") + "oauth2/revoke";
        this.clientId = properties.clientId();
        this.clientSecret = properties.clientSecret();
    }

    public void revokeRefreshToken(String refreshToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        try {
            http.post()
                    .uri(revocationUri)
                    // RFC 6749, §2.3.1: id e segredo codificados antes do Basic.
                    .headers(headers -> headers.setBasicAuth(encode(clientId), encode(clientSecret)))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Não foi possível revogar o refresh token no logout: {}", ex.getClass().getSimpleName());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
