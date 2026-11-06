package br.com.encurtador.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** @param allowedOrigins origens que podem chamar {@code /oauth2/token} do navegador; vazia por padrão */
@ConfigurationProperties("app.cors")
record AuthServerCorsProperties(@DefaultValue List<String> allowedOrigins) {
}
