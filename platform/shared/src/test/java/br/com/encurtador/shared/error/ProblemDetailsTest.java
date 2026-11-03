package br.com.encurtador.shared.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsTest {

    @Test
    void keepsCodeAlreadyPresent() {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.GONE, "LINK_EXPIRED", "O link expirou.");

        ProblemDetails.complete(problem, "GONE");

        assertThat(problem.getProperties()).containsEntry(ProblemDetails.CODE, "LINK_EXPIRED");
        assertThat(problem.getType()).hasToString("urn:encurtador:problem:link-expired");
        assertThat(problem.getTitle()).isEqualTo("Recurso indisponível");
    }

    @Test
    void derivesDefaultCodeAndTitleForUnmappedStatuses() {
        assertThat(ProblemDetails.defaultCode(HttpStatus.I_AM_A_TEAPOT)).isEqualTo("I_AM_A_TEAPOT");
        assertThat(ProblemDetails.defaultCode(HttpStatusCode.valueOf(599))).isEqualTo("HTTP_599");
        assertThat(ProblemDetails.titleFor(418)).isEqualTo("I'm a teapot");
        assertThat(ProblemDetails.titleFor(599)).isEqualTo("Erro");
    }
}
