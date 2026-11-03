package br.com.encurtador.app;

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
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentação OpenAPI, servida só no perfil {@code local} (ADR-0001, D1 e D12). Os erros seguem o RFC 9457 com
 * {@code code} e {@code errors[]} na raiz do JSON, e não dentro de {@code properties} como no schema padrão do
 * {@code ProblemDetail}: por isso o schema {@code Problem} é declarado aqui. O esquema de segurança bearer é do módulo
 * de segurança (ADR-0002, D10).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    @Bean
    OpenAPI openApi(ObjectProvider<BuildProperties> build) {
        String version = build.stream().map(BuildProperties::getVersion).findFirst().orElse("dev");
        return new OpenAPI()
                .info(new Info()
                        .title("Encurtador de URLs")
                        .version(version)
                        .description("""
                                Links curtos com contas, cache e estatísticas de cliques.

                                Rotas protegidas exigem `Authorization: Bearer <accessToken>`, obtido em \
                                `POST /api/v1/auth/login`. Todo erro é `application/problem+json` com um `code` \
                                estável; as respostas 429 trazem `Retry-After` em segundos."""))
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
