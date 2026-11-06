package br.com.encurtador.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

/**
 * Propriedades do servidor de autorização ({@code app.auth.*}, ADR-0003 D5 e D9; ADR-0004, fase WA).
 *
 * @param audience           {@code aud} dos access tokens: o app só aceita tokens para ele
 * @param signingKey         chave RSA que assina os tokens
 * @param previousSigningKey chave anterior, publicada no JWKS durante a rotação; não assina nada
 * @param panelOrigins       origens do painel para onde o cadastro e a troca de senha podem voltar
 * @param panelDefaultUrl    para onde ir depois do cadastro ou da troca de senha sem uma volta válida
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthServerProperties(
        @NotBlank String audience,
        @NotNull @Valid SigningKey signingKey,
        @Nullable @Valid SigningKey previousSigningKey,
        @NotEmpty List<URI> panelOrigins,
        @NotNull URI panelDefaultUrl) {

    /**
     * @param id       {@code kid} publicado no JWKS e gravado no cabeçalho dos tokens
     * @param location PEM PKCS#8 da chave privada; sem ele, uma chave é gerada na subida e os tokens deixam de valer
     *                 quando o processo reinicia
     */
    public record SigningKey(@NotBlank String id, @Nullable Resource location) {
    }
}
