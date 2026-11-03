package br.com.encurtador.security.web;

import br.com.encurtador.security.UserIdResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Registra o resolvedor de {@code @CurrentUser} no Spring MVC. */
@Configuration(proxyBeanMethods = false)
class SecurityWebConfig implements WebMvcConfigurer {

    private final UserIdResolver users;

    SecurityWebConfig(UserIdResolver users) {
        this.users = users;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(users));
    }
}
