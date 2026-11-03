package br.com.encurtador.security;

/** Assunto de token que o {@link UserIdResolver} não reconhece; vira 401 com o código de token inválido. */
public class UnknownSubjectException extends RuntimeException {

    public UnknownSubjectException(String message) {
        super(message);
    }
}
