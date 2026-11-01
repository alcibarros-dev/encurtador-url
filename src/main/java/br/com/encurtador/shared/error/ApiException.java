package br.com.encurtador.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Erro de negócio com status HTTP e código estável, convertido em ProblemDetail pelo handler global.
 * <p>
 * A mensagem vira o campo {@code detail} da resposta: deve ser segura para o cliente e escrita em português.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
