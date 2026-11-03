package br.com.encurtador.security.web;

import br.com.encurtador.security.UserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Exceções de segurança lançadas no MVC voltam ao Spring Security, que escreve o 401 e o 403 (ADR-0002, D4). Os
 * filtros ficam de fora: são cobertos pelo teste de integração do app.
 */
@WebMvcTest(controllers = SecurityExceptionAdviceTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(SecurityExceptionAdviceTest.ProbeController.class)
class SecurityExceptionAdviceTest {

    @Autowired
    MockMvc mvc;

    /** Exigido pelo resolvedor de {@code @CurrentUser}, que entra no slice como {@code WebMvcConfigurer}. */
    @MockitoBean
    UserIdResolver users;

    @Test
    void accessDeniedIsLeftToSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(get("/probe/denied")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void authenticationFailureIsLeftToSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(get("/probe/unauthenticated")))
                .hasRootCauseInstanceOf(BadCredentialsException.class);
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("negado");
        }

        @GetMapping("/unauthenticated")
        void unauthenticated() {
            throw new BadCredentialsException("credenciais inválidas");
        }
    }
}
