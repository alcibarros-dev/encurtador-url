package br.com.encurtador.link.domain;

/**
 * Valor que viola uma regra do domínio. A mensagem completa o nome do campo na resposta 400 ("url: deve usar http
 * ou https"): em português, em minúsculas e sem dados internos.
 */
public class InvalidValueException extends RuntimeException {

    public InvalidValueException(String message) {
        super(message);
    }
}
