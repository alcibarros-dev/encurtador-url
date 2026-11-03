package br.com.encurtador.link.application;

import br.com.encurtador.link.domain.ShortCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Códigos base62 aleatórios de {@code app.links.code-length} caracteres (ADR-0001, D3): imprevisíveis, sem coordenação
 * entre instâncias e sem vazar o volume de links, como faria uma sequência.
 */
@Component
public class RandomShortCodeGenerator implements ShortCodeGenerator {

    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** Compartilhado e thread-safe; {@code nextInt(bound)} não tem o viés do módulo. */
    private final SecureRandom random = new SecureRandom();
    private final int length;

    public RandomShortCodeGenerator(LinkProperties properties) {
        this.length = properties.codeLength();
    }

    @Override
    public String next() {
        String code;
        do {
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = BASE62[random.nextInt(BASE62.length)];
            }
            code = new String(chars);
        } while (ShortCode.isReserved(code)); // só possível com 6 caracteres ("health", "static"…)
        return code;
    }
}
