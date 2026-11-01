package br.com.encurtador.shared.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato de erro RFC 9457 da API (ADR-0001, D12). Os filtros de segurança ficam de fora: são cobertos pelo
 * teste de integração.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
// Classes aninhadas em testes são excluídas do scan (TestTypeExcludeFilter); o controller de prova entra por @Import.
@Import(GlobalExceptionHandlerTest.ProbeController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiExceptionBecomesProblemDetailWithStableCode() throws Exception {
        mvc.perform(get("/probe/api-exception"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ALIAS_TAKEN"))
                .andExpect(jsonPath("$.type").value("urn:encurtador:problem:alias-taken"))
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Esse alias já está em uso."))
                .andExpect(jsonPath("$.instance").value("/probe/api-exception"));
    }

    @Test
    void invalidBodyListsEveryViolatedField() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"size\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "size")));
    }

    @Test
    void invalidRequestParameterIsReportedAsValidationFailure() throws Exception {
        mvc.perform(get("/probe/param").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void parameterOfTheWrongTypeIsReportedAsValidationFailure() throws Exception {
        mvc.perform(get("/probe/param").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("tem um formato inválido"));
    }

    @Test
    void malformedJsonIsRejectedWithoutLeakingParserDetails() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"));
    }

    @Test
    void unexpectedErrorReturnsGenericMessage() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", not(containsString("sensível"))));
    }

    @Test
    void frameworkErrorsReceiveCodeDerivedFromStatus() throws Exception {
        mvc.perform(get("/probe/inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"));

        mvc.perform(post("/probe/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(get("/probe/denied")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        record Body(@NotBlank String name, @Min(1) int size) {
        }

        @GetMapping("/api-exception")
        void apiException() {
            throw new ApiException(HttpStatus.CONFLICT, "ALIAS_TAKEN", "Esse alias já está em uso.");
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/param")
        void param(@RequestParam @Min(1) int page) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("detalhe interno sensível");
        }

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("negado");
        }
    }
}
