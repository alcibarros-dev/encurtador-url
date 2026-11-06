package br.com.encurtador.link.application;

import br.com.encurtador.link.domain.ShortCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Geração de códigos, validade e cota dos links ({@code app.links.*}, ADR-0001, D3 e D5).
 *
 * @param codeLength       tamanho do código gerado, em caracteres base62
 * @param defaultTtl       validade quando o link é criado sem {@code expiresAt}
 * @param maxTtl           maior validade aceita
 * @param maxActivePerUser cota de links ativos e não expirados por usuário
 * @param serviceUrls      URLs públicas dos outros serviços do encurtador, como o servidor de autorização; os hosts
 *                         delas não podem ser destino de link (ADR-0003, D9)
 */
@Validated
@ConfigurationProperties("app.links")
public record LinkProperties(
        @Min(6) @Max(ShortCode.MAX_LENGTH) int codeLength,
        @NotNull @DurationMin(minutes = 1) Duration defaultTtl,
        @NotNull @DurationMin(days = 1) Duration maxTtl,
        @Min(1) int maxActivePerUser,
        @DefaultValue List<URI> serviceUrls) {

    /** Hosts de {@link #serviceUrls()}; uma URL sem host (relativa) não bloqueia nada. */
    public List<String> serviceHosts() {
        return serviceUrls.stream().map(URI::getHost).filter(host -> host != null && !host.isBlank()).toList();
    }

    @AssertTrue(message = "default-ttl não pode ser maior que max-ttl")
    public boolean isDefaultTtlWithinMaxTtl() {
        return defaultTtl == null || maxTtl == null || defaultTtl.compareTo(maxTtl) <= 0;
    }
}
