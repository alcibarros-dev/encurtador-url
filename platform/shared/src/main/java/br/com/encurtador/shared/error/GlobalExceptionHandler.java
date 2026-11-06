package br.com.encurtador.shared.error;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Converte toda exceção que chega ao MVC em {@code application/problem+json} (ADR-0001, D12).
 * Erros gerados em filtros (401, 403, 429) não passam por aqui e usam {@link ProblemDetails} diretamente. As exceções
 * do Spring Security lançadas no MVC são devolvidas a ele por um advice do módulo de segurança, que vem antes deste.
 * <p>
 * Uma aplicação com páginas HTML (o servidor de autorização, ADR-0003) o desliga com
 * {@code app.errors.problem-details=false} e usa as páginas de erro do Spring Boot.
 */
@RestControllerAdvice
@ConditionalOnBooleanProperty(name = "app.errors.problem-details", matchIfMissing = true)
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String VALIDATION_DETAIL = "Um ou mais campos são inválidos.";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetails.of(ex.status(), ex.code(), ex.getMessage());
        if (ex instanceof InvalidFieldsException invalid) {
            problem.setProperty(ProblemDetails.ERRORS, invalid.violations());
        }
        return ResponseEntity.status(ex.status()).headers(ex.headers()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Erro inesperado ao processar a requisição", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(ProblemDetails.of(status, INTERNAL_ERROR, "Ocorreu um erro inesperado. Tente novamente."));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.add(new FieldViolation(error.getField(), error.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        return validationProblem(ex, errors, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : parameter;
                errors.add(new FieldViolation(field, error.getDefaultMessage()));
            }
        });
        return validationProblem(ex, errors, headers, status, request);
    }

    /** Parâmetro que não converte para o tipo esperado ({@code page=abc}, {@code from=ontem}): 400 com o campo. */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException argument ? argument.getName()
                : ex.getPropertyName();
        return validationProblem(ex, List.of(new FieldViolation(field, "tem um formato inválido")), headers, status,
                request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(status, MALFORMED_REQUEST,
                "O corpo da requisição está ausente ou malformado.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            ProblemDetails.complete(problem, ProblemDetails.defaultCode(statusCode));
        }
        return response;
    }

    private @Nullable ResponseEntity<Object> validationProblem(
            Exception ex, List<FieldViolation> errors, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(status, VALIDATION_FAILED, VALIDATION_DETAIL);
        problem.setProperty(ProblemDetails.ERRORS, errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
