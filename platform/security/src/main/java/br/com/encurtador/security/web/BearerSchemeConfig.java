package br.com.encurtador.security.web;

import br.com.encurtador.security.CurrentUser;
import br.com.encurtador.security.OpenApiSecurity;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Esquema bearer da documentação OpenAPI, usado pelos {@code @SecurityRequirement} dos controllers protegidos. O
 * parâmetro {@code @CurrentUser} vem do token e não aparece na documentação.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenApiCustomizer.class)
class BearerSchemeConfig {

    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Bean
    OpenApiCustomizer bearerScheme() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSecuritySchemes(OpenApiSecurity.BEARER, new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token de 15 minutos"));
        };
    }
}
