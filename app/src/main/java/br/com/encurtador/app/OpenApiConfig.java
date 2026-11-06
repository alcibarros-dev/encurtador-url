package br.com.encurtador.app;

import br.com.encurtador.security.OpenApiSecurity;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentação OpenAPI, servida só no perfil {@code local} (ADR-0001, D1 e D12). Os erros seguem o RFC 9457 com
 * {@code code} e {@code errors[]} na raiz do JSON, e não dentro de {@code properties} como no schema padrão do
 * {@code ProblemDetail}: por isso o schema {@code Problem} é declarado aqui. O esquema de segurança bearer é do módulo
 * de segurança (ADR-0002, D10); o login pelo servidor de autorização no Swagger UI, só do perfil {@code local}, é
 * daqui (ADR-0003, D10).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";
    static final String OAUTH2 = "oauth2";

    @Bean
    OpenAPI openApi(ObjectProvider<BuildProperties> build) {
        String version = build.stream().map(BuildProperties::getVersion).findFirst().orElse("dev");
        return new OpenAPI()
                .info(new Info()
                        .title("Encurtador de URLs")
                        .version(version)
                        .description("""
                                Links curtos com contas, cache e estatísticas de cliques.

                                Rotas protegidas exigem `Authorization: Bearer <accessToken>`, emitido pelo servidor \
                                de autorização (`auth-server`, OAuth 2.1 e OpenID Connect). Todo erro é \
                                `application/problem+json` com um `code` estável; as respostas 429 trazem \
                                `Retry-After` em segundos, e a 503 `AUTH_UNAVAILABLE` também."""))
                .components(new Components().addSchemas("Problem", problemSchema()));
    }

    /**
     * Toda resposta de erro tem o corpo {@code Problem}. Sem isto, o springdoc repete nos erros o schema de sucesso
     * do método.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                operation.getResponses().forEach((status, response) -> {
                    if (status.startsWith("4") || status.startsWith("5")) {
                        response.setContent(new Content().addMediaType(PROBLEM_JSON,
                                new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))));
                    }
                })));
    }

    /**
     * Login no botão "Authorize" do Swagger UI pelo fluxo de código com PKCE (ADR-0003, D10). Toda operação que aceita
     * o bearer passa a aceitar também o token obtido por esse login; colar um token continua possível.
     */
    @Bean
    @ConditionalOnProperty("app.openapi.oauth2.authorization-url")
    OpenApiCustomizer oauth2Login(@Value("${app.openapi.oauth2.authorization-url}") String authorizationUrl,
                                  @Value("${app.openapi.oauth2.token-url}") String tokenUrl) {
        return openApi -> {
            openApi.getComponents().addSecuritySchemes(OAUTH2, new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .description("Login no servidor de autorização (cliente encurtador-dev)")
                    .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                            .authorizationUrl(authorizationUrl)
                            .tokenUrl(tokenUrl)
                            .scopes(new Scopes()
                                    .addString("openid", "Identificação do usuário")
                                    .addString("email", "E-mail no id_token")))));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getSecurity() != null && operation.getSecurity().stream()
                        .anyMatch(requirement -> requirement.containsKey(OpenApiSecurity.BEARER))) {
                    operation.addSecurityItem(new SecurityRequirement().addList(OAUTH2));
                }
            }));
        };
    }

    private static Schema<?> problemSchema() {
        Schema<?> violation = new ObjectSchema()
                .addProperty("field", new StringSchema().example("url"))
                .addProperty("message", new StringSchema().example("deve usar http ou https"));
        return new ObjectSchema()
                .description("Erro RFC 9457")
                .addProperty("type", new StringSchema().example("urn:encurtador:problem:alias-taken"))
                .addProperty("title", new StringSchema().example("Conflito"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty("detail", new StringSchema().example("Este alias já está em uso."))
                .addProperty("instance", new StringSchema().example("/api/v1/links"))
                .addProperty("code", new StringSchema().example("ALIAS_TAKEN")
                        .description("Código estável, para tratamento por máquina"))
                .addProperty("errors", new ArraySchema().items(violation)
                        .description("Campos inválidos; só em `VALIDATION_FAILED`"));
    }
}
