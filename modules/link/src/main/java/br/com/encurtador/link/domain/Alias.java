package br.com.encurtador.link.domain;

/**
 * Código escolhido pelo usuário (ADR-0001, D3). Divide a coluna {@code code} com os códigos gerados; a unicidade é
 * garantida pelo banco, não por consulta prévia.
 */
public record Alias(String value) {

    public Alias {
        if (!ShortCode.isWellFormed(value)) {
            throw new InvalidValueException("deve ter de 4 a 32 caracteres, só letras sem acento, números, '_' e '-'");
        }
        if (ShortCode.isReserved(value)) {
            throw new InvalidValueException("é uma palavra reservada");
        }
    }
}
