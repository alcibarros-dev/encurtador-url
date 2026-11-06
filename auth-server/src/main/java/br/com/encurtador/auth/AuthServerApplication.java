package br.com.encurtador.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servidor de autorização OAuth 2 e OpenID Connect do encurtador (ADR-0003): credenciais, login, cadastro, troca de
 * senha e emissão de tokens. A plataforma ({@code shared} e {@code ratelimit}) chega por auto-configuração, sem que
 * este pacote escaneie {@code br.com.encurtador} (ADR-0002, D11).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
