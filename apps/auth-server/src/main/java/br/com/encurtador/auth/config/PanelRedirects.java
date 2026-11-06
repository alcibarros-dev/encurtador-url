package br.com.encurtador.auth.config;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Para onde o cadastro e a troca de senha podem voltar (ADR-0004, D4 e fase WA): só para as origens do painel
 * configuradas. Qualquer outro endereço vira o padrão, para a volta não servir de redirecionamento aberto.
 */
@Component
public class PanelRedirects {

    private final AuthServerProperties properties;

    PanelRedirects(AuthServerProperties properties) {
        this.properties = properties;
    }

    public String target(@Nullable String requested) {
        return isAllowed(requested) ? requested : properties.panelDefaultUrl().toString();
    }

    public boolean isAllowed(@Nullable String requested) {
        if (requested == null || requested.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(requested);
            return uri.isAbsolute() && uri.getRawUserInfo() == null
                    && properties.panelOrigins().stream().anyMatch(origin -> sameOrigin(origin, uri));
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static boolean sameOrigin(URI origin, URI uri) {
        return origin.getScheme().equalsIgnoreCase(uri.getScheme())
                && origin.getHost().equalsIgnoreCase(uri.getHost())
                && port(origin) == port(uri);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
