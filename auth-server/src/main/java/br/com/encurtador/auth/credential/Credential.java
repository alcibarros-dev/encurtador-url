package br.com.encurtador.auth.credential;

/**
 * Credencial de um usuário (ADR-0003, D8).
 *
 * @param subject {@code sub} dos tokens: o id antigo do app em texto, para os usuários importados, ou um UUID
 */
public record Credential(long id, String subject, String email, String passwordHash) {
}
