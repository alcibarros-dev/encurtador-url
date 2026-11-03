package br.com.encurtador.security;

/** Nome do esquema de segurança da documentação OpenAPI, para o {@code @SecurityRequirement} dos controllers. */
public final class OpenApiSecurity {

    public static final String BEARER = "bearerAuth";

    private OpenApiSecurity() {
    }
}
