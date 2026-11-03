package br.com.encurtador.shared.error;

/**
 * Item de {@code errors[]} numa resposta 400 de validação.
 */
public record FieldViolation(String field, String message) {
}
