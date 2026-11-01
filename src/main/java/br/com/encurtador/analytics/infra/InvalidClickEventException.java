package br.com.encurtador.analytics.infra;

/** Mensagem que nunca vai ser gravada: vai direto para a DLT, sem retry. */
class InvalidClickEventException extends RuntimeException {

    InvalidClickEventException(String message) {
        super(message);
    }

    InvalidClickEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
