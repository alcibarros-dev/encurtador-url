package br.com.encurtador.shared.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 400 por regra de domínio violada em campos da requisição, no mesmo formato do Bean Validation
 * ({@code VALIDATION_FAILED} com {@code errors[]}). Serve às regras que uma anotação expressa mal: as que dependem do
 * {@code Clock}, da configuração ou de um value object do domínio.
 */
public class InvalidFieldsException extends ApiException {

    private final List<FieldViolation> violations;

    public InvalidFieldsException(List<FieldViolation> violations) {
        super(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.VALIDATION_FAILED, GlobalExceptionHandler.VALIDATION_DETAIL);
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
