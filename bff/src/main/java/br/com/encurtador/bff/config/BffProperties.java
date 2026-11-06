package br.com.encurtador.bff.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Configuração do BFF ({@code bff.*}, ADR-0004 D4, D5 e D12). O auth-server tem dois endereços: o público, para onde
 * o navegador vai (autorização, cadastro, troca de senha, logout, e o {@code iss} dos tokens), e o interno, que o BFF
 * chama (token, JWKS, revogação).
 *
 * @param apiUrl            endereço interno do app, para onde vai o repasse de {@code /api/**}
 * @param authPublicUrl     URL pública do auth-server, que também é o emissor dos tokens
 * @param authInternalUrl   endereço do auth-server pela rede interna
 * @param clientId          cliente confidencial do painel no auth-server
 * @param clientSecret      segredo dele
 * @param registrationUrl   página de cadastro do provedor
 * @param passwordChangeUrl página de troca de senha do provedor
 * @param cookies           nomes e atributos dos cookies de sessão e de CSRF
 * @param connectTimeout    tempo máximo para conectar ao app
 * @param readTimeout       tempo máximo de espera pela resposta do app
 */
@Validated
@ConfigurationProperties("bff")
public record BffProperties(
        @NotNull URI apiUrl,
        @NotNull URI authPublicUrl,
        @NotNull URI authInternalUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotNull URI registrationUrl,
        @NotNull URI passwordChangeUrl,
        @DefaultValue Cookies cookies,
        @DefaultValue("1s") @NotNull Duration connectTimeout,
        @DefaultValue("10s") @NotNull Duration readTimeout) {

    /**
     * Fora do ambiente local, os cookies levam o prefixo {@code __Host-} e o atributo {@code Secure}; os de
     * {@code localhost} não se separam por porta, por isso os nomes são próprios do painel (ADR-0004, D4).
     *
     * @param csrfName nome do cookie de CSRF, legível pelo JavaScript
     * @param secure   {@code Secure} nos cookies
     */
    public record Cookies(@DefaultValue("__Host-XSRF-TOKEN") @NotBlank String csrfName,
                          @DefaultValue("true") boolean secure) {
    }

    @Override
    public String toString() {
        return "BffProperties[apiUrl=%s, authPublicUrl=%s, authInternalUrl=%s, clientId=%s, clientSecret=***]"
                .formatted(apiUrl, authPublicUrl, authInternalUrl, clientId);
    }
}
