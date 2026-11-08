package br.com.encurtador.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Limites por regra ({@code app.rate-limit.<regra>}, ADR-0001, D11), cada um uma janela fixa. As regras são declaradas
 * pelos módulos donos das rotas (ADR-0002, D5), e o nome da regra é a chave do mapa: na API, {@code link-creation} e
 * {@code user-stats}; no serviço de redirecionamento, {@code redirect} (ADR-0006, D6); no BFF, as do painel. O
 * auth-server, com repositório próprio, tem uma cópia deste módulo para os limites dele (ADR-0003, D9; ADR-0007).
 */
@Validated
@ConfigurationProperties("app")
public record RateLimitProperties(@NotEmpty Map<String, @Valid Limit> rateLimit) {

    public record Limit(@Min(1) long requests, @NotNull @DurationMin(seconds = 1) Duration window) {
    }
}
